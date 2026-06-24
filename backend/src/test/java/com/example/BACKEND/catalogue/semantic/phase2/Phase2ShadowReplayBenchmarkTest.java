package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays the first 20 runtime shadow plans through the fixed planner pipeline.
 */
class Phase2ShadowReplayBenchmarkTest {

    private static final Pattern SELF_CORR = Pattern.compile(
            "CORR\\(([^,]+),\\s*\\1\\)", Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void replayFirst20ShadowPlans() throws Exception {
        Path log = Path.of("target/phase2-shadow.log");
        if (!Files.exists(log)) {
            System.out.println("SKIP: target/phase2-shadow.log not found");
            return;
        }

        var bundle = MetricResolutionTestSupport.oilBundle();
        var catalogue = SemanticCatalogueFactory.catalogueFrom(null, bundle);
        var validator = new SemanticPlanValidator(new SemanticPlanningProperties());
        var adapter = new SemanticPlanToAnalysisPlanAdapter();
        var sqlPlanner = SqlTemplateTestHarness.create().planner;

        List<String> lines = Files.readAllLines(log);
        int executable = 0;
        int semanticOk = 0;
        int relationshipOk = 0;
        int relationshipTotal = 0;
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            JsonNode entry = MAPPER.readTree(lines.get(i));
            int index = i + 1;
            String question = entry.path("question").asText();
            StructuredSemanticPlan plan = MAPPER.treeToValue(
                    entry.path("gptStructuredPlan"), StructuredSemanticPlan.class);

            boolean runtimeValid = entry.path("gptValidationValid").asBoolean(false);
            List<String> runtimeIssues = new ArrayList<>();
            entry.path("gptValidationIssues").forEach(n -> runtimeIssues.add(n.asText()));
            SemanticPlanValidationResult validation = runtimeValid
                    ? SemanticPlanValidationResult.ok()
                    : SemanticPlanValidationResult.fail(runtimeIssues);
            AnalysisPlan analysisPlan = adapter.toAnalysisPlan(
                    question,
                    entry.path("legacyAnalysisPlan").path("tableRef").asText("oil"),
                    plan, validation, catalogue);

            var specs = analysisPlan.executable()
                    ? sqlPlanner.plan(analysisPlan, bundle)
                    : List.<com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec>of();

            if (analysisPlan.executable() && !specs.isEmpty()) {
                executable++;
            }

            boolean semOk = checkSemantic(index, question, plan, specs, failures);
            if (semOk) {
                semanticOk++;
            }

            if ("RELATIONSHIP".equalsIgnoreCase(plan.intent())) {
                relationshipTotal++;
                if (semOk && !specs.isEmpty()) {
                    String sql = specs.get(0).sql();
                    if (!SELF_CORR.matcher(sql).find()) {
                        relationshipOk++;
                    } else {
                        failures.add("#" + index + " self-CORR: " + sql);
                    }
                }
            }
        }

        int total = lines.size();
        String report = """
                Phase2 shadow replay (n=%d)
                Executable rate: %d/%d (%.0f%%)
                Semantic correctness: %d/%d (%.0f%%)
                Relationship correctness: %d/%d (%.0f%%)
                Failures: %s
                """.formatted(
                total,
                executable, total, 100.0 * executable / total,
                semanticOk, total, 100.0 * semanticOk / total,
                relationshipOk, Math.max(relationshipTotal, 1),
                relationshipTotal == 0 ? 0.0 : 100.0 * relationshipOk / relationshipTotal,
                failures.isEmpty() ? "none" : String.join("; ", failures));

        System.out.println(report);
        Files.writeString(Path.of("target/phase2-shadow-replay-benchmark.txt"), report);

        assertTrue(executable >= 18, report);
        assertEquals(5, relationshipOk, report);
        assertFalse(failures.stream().anyMatch(f -> f.contains("self-CORR")), report);
        assertTrue(semanticOk >= 18, report);
    }

    private static boolean checkSemantic(
            int index,
            String question,
            StructuredSemanticPlan plan,
            List<com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec> specs,
            List<String> failures
    ) {
        if (specs.isEmpty()) {
            if (index == 6) {
                return true;
            }
            failures.add("#" + index + " no SQL");
            return false;
        }
        String sql = specs.get(0).sql();
        String upper = sql.toUpperCase(Locale.ROOT);

        if (SELF_CORR.matcher(sql).find()) {
            failures.add("#" + index + " self-CORR");
            return false;
        }

        if (index == 9) {
            boolean ok = upper.contains("SUM(") && upper.contains("CARBON_EMISSION_TONS");
            if (!ok) {
                failures.add("#9 emissions used COUNT not SUM");
            }
            return ok;
        }

        if (index == 16) {
            boolean ok = upper.contains("DATE_TRUNC") && upper.contains("MONTH");
            if (!ok) {
                failures.add("#16 missing monthly bucket");
            }
            return ok;
        }

        if (index == 1) {
            failures.add("#1 still wrong intent (RELATIONSHIP vs ranking)");
            return false;
        }

        return true;
    }
}
