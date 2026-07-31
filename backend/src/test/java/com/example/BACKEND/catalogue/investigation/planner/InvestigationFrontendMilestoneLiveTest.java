package com.example.BACKEND.catalogue.investigation.planner;

import com.example.BACKEND.catalogue.investigation.EvidenceRequestSqlCompiler;
import com.example.BACKEND.catalogue.investigation.InvestigationObjectiveParser;
import com.example.BACKEND.catalogue.investigation.InvestigationPlan;
import com.example.BACKEND.catalogue.investigation.InvestigationPlanParser;
import com.example.BACKEND.catalogue.investigation.SlicePredicateBuilder;
import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.GptStructuredCompletionClient;
import com.example.BACKEND.catalogue.semantic.phase2.GptStructuredSemanticPlanner;
import com.example.BACKEND.catalogue.semantic.phase2.SchemaSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Focused live traces for the six frontend milestone questions.
 */
class InvestigationFrontendMilestoneLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> QUESTIONS = List.of(
            "How does trip distance influence total amount?",
            "Why do payment type 1 users tip more than payment type 2?",
            "Which customers contribute most revenue?",
            "How has revenue changed over time?",
            "Which regions generate the highest revenue?",
            "Is revenue concentrated among a few customers?"
    );

    @Test
    @DisplayName("Frontend milestone investigation plan traces")
    void traceFrontendMilestoneQuestions() throws Exception {
        String apiKey = resolveProperty("OPENAI_API_KEY", "openai.api.key");
        String model = resolveProperty("OPENAI_MODEL", "openai.model");
        assumeTrue(isUsableKey(apiKey), "No usable OpenAI API key — skipping milestone traces");

        GptStructuredCompletionClient client = new GptStructuredCompletionClient(apiKey, model, MAPPER);
        GptStructuredSemanticPlanner planner = new GptStructuredSemanticPlanner(client, MAPPER);
        InvestigationPlanParser planParser = new InvestigationPlanParser();
        InvestigationObjectiveParser objectiveParser = new InvestigationObjectiveParser();
        EvidenceRequestSqlCompiler compiler =
                new EvidenceRequestSqlCompiler(new SlicePredicateBuilder());

        ApprovedCatalogueSnapshot catalogue = InvestigationPlannerFixtures.catalogue();
        SchemaSnapshot schema = InvestigationPlannerFixtures.schema();

        ArrayNode out = MAPPER.createArrayNode();
        for (String question : QUESTIONS) {
            ObjectNode row = MAPPER.createObjectNode();
            row.put("question", question);
            try {
                StructuredSemanticPlan plan = planner.plan(question, catalogue, schema);
                row.put("execution_mode", plan.executionMode());
                row.set("investigation_plan", plan.investigationPlan());
                row.set("investigation_objective", plan.investigationObjective());

                if (plan.investigationPlan() != null && !plan.investigationPlan().isNull()) {
                    InvestigationPlan typed = planParser.parse(plan.investigationPlan(), objectiveParser);
                    ArrayNode reqs = row.putArray("compiled");
                    var compiled = compiler.compile(typed, catalogue.tableRef());
                    for (var cr : compiled.requests()) {
                        ObjectNode c = reqs.addObject();
                        c.put("request_id", cr.request().requestId());
                        c.put("kind", cr.request().kind());
                        c.put("ok", cr.ok());
                        if (cr.ok()) {
                            c.put("sql", cr.spec().sql());
                        } else {
                            c.put("error", cr.error());
                        }
                    }
                }
            } catch (Exception e) {
                row.put("error", e.getMessage());
            }
            out.add(row);
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(row));
        }

        Path path = Path.of("target/investigation-frontend-milestone-traces.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        System.out.println("Wrote " + path.toAbsolutePath());
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
        try (InputStream in = InvestigationFrontendMilestoneLiveTest.class
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
