package com.example.BACKEND.catalogue.investigation.planner;

import com.example.BACKEND.catalogue.investigation.EvidenceRequestSqlCompiler;
import com.example.BACKEND.catalogue.investigation.InvestigationPlan;
import com.example.BACKEND.catalogue.investigation.InvestigationPlanParser;
import com.example.BACKEND.catalogue.investigation.InvestigationObjectiveParser;
import com.example.BACKEND.catalogue.investigation.SlicePredicateBuilder;
import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.GptStructuredCompletionClient;
import com.example.BACKEND.catalogue.semantic.phase2.GptStructuredSemanticPlanner;
import com.example.BACKEND.catalogue.semantic.phase2.SchemaSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live audit: InvestigationPlan evidence programs for 50+ questions.
 * Validates evidence_requests emission and compiler coverage — not categories.
 */
class InvestigationPlanEvidenceQualityLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("InvestigationPlan evidence quality suite (50+ questions)")
    void auditInvestigationPlanEvidenceQuality() throws Exception {
        String apiKey = resolveProperty("OPENAI_API_KEY", "openai.api.key");
        String model = resolveProperty("OPENAI_MODEL", "openai.model");
        assumeTrue(isUsableKey(apiKey), "No usable OpenAI API key — skipping live evidence-plan audit");

        GptStructuredCompletionClient client = new GptStructuredCompletionClient(apiKey, model, MAPPER);
        GptStructuredSemanticPlanner planner = new GptStructuredSemanticPlanner(client, MAPPER);
        InvestigationPlanParser planParser = new InvestigationPlanParser();
        InvestigationObjectiveParser objectiveParser = new InvestigationObjectiveParser();
        EvidenceRequestSqlCompiler compiler =
                new EvidenceRequestSqlCompiler(new SlicePredicateBuilder());

        ApprovedCatalogueSnapshot catalogue = InvestigationPlannerFixtures.catalogue();
        SchemaSnapshot schema = InvestigationPlannerFixtures.schema();
        List<String> questions = new ArrayList<>(InvestigationPlannerQuestionBank.questions());
        questions.addAll(List.of(
                "How does trip distance influence total amount?",
                "Why do payment type 1 users tip more than payment type 2?",
                "Which customers contribute most revenue?",
                "How has revenue changed over time?",
                "Which regions generate the highest revenue?",
                "Is revenue concentrated among a few customers?"
        ));
        assertTrue(questions.size() >= 50);

        ArrayNode rows = MAPPER.createArrayNode();
        int investigationCount = 0;
        int withPlan = 0;
        int withRequests = 0;
        int compileOk = 0;
        int categoryLeak = 0;
        Map<String, Integer> kindCounts = new LinkedHashMap<>();
        Set<String> unsupportedKinds = new LinkedHashSet<>();

        for (String question : questions) {
            ObjectNode row = MAPPER.createObjectNode();
            row.put("question", question);
            try {
                StructuredSemanticPlan plan = planner.plan(question, catalogue, schema);
                row.put("execution_mode", plan.executionMode());
                boolean investigation = StructuredSemanticPlan.MODE_INVESTIGATION
                        .equalsIgnoreCase(plan.executionMode());
                if (investigation) {
                    investigationCount++;
                }

                JsonNode rawPlan = plan.investigationPlan();
                boolean hasPlan = rawPlan != null && !rawPlan.isNull() && !rawPlan.isMissingNode();
                row.put("has_investigation_plan", hasPlan);
                if (hasPlan) {
                    withPlan++;
                    if (leaksCategory(rawPlan)) {
                        categoryLeak++;
                        row.put("category_leak", true);
                    }
                    InvestigationPlan typed = planParser.parse(rawPlan, objectiveParser);
                    int requestCount = typed != null && typed.evidenceRequests() != null
                            ? typed.evidenceRequests().size() : 0;
                    row.put("evidence_request_count", requestCount);
                    if (requestCount > 0) {
                        withRequests++;
                        ArrayNode kinds = row.putArray("kinds");
                        boolean allCompile = true;
                        var compiled = compiler.compile(typed, catalogue.tableRef());
                        for (var cr : compiled.requests()) {
                            String kind = cr.request().kind();
                            kinds.add(kind);
                            kindCounts.merge(kind == null ? "(null)" : kind, 1, Integer::sum);
                            if (!cr.ok()) {
                                allCompile = false;
                                unsupportedKinds.add(kind + " :: " + cr.error());
                            }
                        }
                        row.put("all_compile_ok", allCompile);
                        if (allCompile) {
                            compileOk++;
                        }
                    }
                }
            } catch (Exception e) {
                row.put("error", e.getMessage());
            }
            rows.add(row);
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(row));
        }

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("total_questions", questions.size());
        summary.put("investigation_mode_count", investigationCount);
        summary.put("with_investigation_plan", withPlan);
        summary.put("with_evidence_requests", withRequests);
        summary.put("all_requests_compile_ok", compileOk);
        summary.put("category_leak_count", categoryLeak);
        summary.set("kind_frequency", MAPPER.valueToTree(kindCounts));
        summary.set("unsupported_kind_errors", MAPPER.valueToTree(unsupportedKinds));
        summary.set("rows", rows);

        Path out = Path.of("target/investigation-plan-evidence-quality-report.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        System.out.println("Wrote " + out.toAbsolutePath());
        System.out.printf(
                "investigation=%d plan=%d requests=%d compileOk=%d categoryLeak=%d%n",
                investigationCount, withPlan, withRequests, compileOk, categoryLeak);

        // Soft gate: when INVESTIGATION is chosen, plan+requests should exist and compile.
        if (investigationCount > 0) {
            assertTrue(withRequests > 0, "INVESTIGATION plans must emit evidence_requests");
            assertTrue(categoryLeak == 0, "investigation_plan must not emit category/playbook fields");
        }
    }

    private static boolean leaksCategory(JsonNode plan) {
        if (plan == null || !plan.isObject()) {
            return false;
        }
        String[] forbidden = {
                "investigation_type", "question_category", "playbook", "playbook_type",
                "analysis_family", "reasoning_mode", "category"
        };
        for (String f : forbidden) {
            if (plan.has(f) && !plan.get(f).isNull()) {
                return true;
            }
        }
        return false;
    }

    private static String resolveProperty(String env, String prop) throws Exception {
        String fromEnv = System.getenv(env);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromSys = System.getProperty(prop);
        if (fromSys != null && !fromSys.isBlank()) {
            return fromSys.trim();
        }
        try (InputStream in = InvestigationPlanEvidenceQualityLiveTest.class
                .getClassLoader().getResourceAsStream("application.properties")) {
            if (in == null) {
                return null;
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty(prop);
            return v == null ? null : v.trim();
        }
    }

    private static boolean isUsableKey(String key) {
        return key != null && !key.isBlank() && !key.contains("YOUR_") && key.length() > 10;
    }
}
