package com.example.BACKEND.catalogue.decision.synthesis.answer;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.governance.MetricDecompositionBinding;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalReasoningPlan;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import com.example.BACKEND.catalogue.decision.planning.PlanDepth;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModelAdapter;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryValidator;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalSqlRenderer;
import com.example.BACKEND.catalogue.semantic.canonical.SqlFidelityBenchmarkRunner;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticCatalogueFactory;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanningProperties;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanToAnalysisPlanAdapter;
import com.example.BACKEND.catalogue.semantic.phase2.GptStructuredCompletionClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the end-to-end invariant:
 * Question → CanonicalQueryModel → Canonical SQL → Warehouse rows → English answer
 * with no legacy evidence injection and no ungrounded numbers.
 */
class CanonicalAnswerGroundingInvariantTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CanonicalQueryModelAdapter adapter = new CanonicalQueryModelAdapter();
    private final CanonicalQueryValidator validator =
            new CanonicalQueryValidator(new SemanticPlanningProperties());
    private final CanonicalSqlRenderer renderer = new CanonicalSqlRenderer();

    record GroundingCase(int index, String question, StructuredSemanticPlan plan) {}

    static Stream<GroundingCase> embeddedBenchmarkCases() {
        return Stream.of(
                new GroundingCase(0, "Revenue by region",
                        plan("DISTRIBUTION", "total_revenue", null,
                                List.of("region"), agg("SUM", null), null, null, null, null)),
                new GroundingCase(1, "Average profit margin by oil field",
                        plan("COMPARISON", "profit_margin", null,
                                List.of("oil_field"), agg("AVG", null),
                                order("profit_margin", "DESC"), 5, null, null)),
                new GroundingCase(2, "What is total revenue?",
                        plan("SCALAR", "total_revenue", null,
                                List.of(), agg("SUM", null), null, null, null, null)),
                new GroundingCase(3, "Monthly revenue trend",
                        plan("TREND", "total_revenue", null,
                                List.of("recorded_date"), agg("SUM", null),
                                null, null, null, "MONTH")),
                new GroundingCase(4, "Top regions by revenue",
                        plan("RANKING", "total_revenue", null,
                                List.of("region"), agg("SUM", null),
                                order("total_revenue", "DESC"), 3, null, null)),
                new GroundingCase(5, "Correlation between profit margin and downtime",
                        plan("RELATIONSHIP", "profit_margin", "downtime_hours",
                                List.of(), agg(null, null), null, null, "downtime_hours", null))
        );
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("embeddedBenchmarkCases")
    void embeddedBenchmarkCasesSatisfyGroundingInvariant(GroundingCase testCase) throws Exception {
        runInvariantForPlan(testCase.index(), testCase.question(), testCase.plan());
    }

    @Test
    void replayStoredBenchmarkCasesWhenArtifactsPresent() throws Exception {
        Path gap = Path.of("target/phase2-gap-analysis.json");
        Path shadowFirst20 = Path.of("target/phase2-shadow-first20.json");
        Path fidelityLog = Path.of("target/semantic-fidelity.log");
        Path shadowLog = Path.of("target/phase2-shadow.log");

        if (!Files.exists(gap) && !Files.exists(shadowFirst20)
                && !Files.exists(fidelityLog) && !Files.exists(shadowLog)) {
            return;
        }

        var bundle = MetricResolutionTestSupport.oilBundle();
        var catalogue = SemanticCatalogueFactory.catalogueFrom(null, bundle);
        var analysisAdapter = new SemanticPlanToAnalysisPlanAdapter();
        var sqlPlanner = SqlTemplateTestHarness.create().planner;

        List<SqlFidelityBenchmarkRunner.BenchmarkCase> cases =
                SqlFidelityBenchmarkRunner.loadCases(
                        gap, shadowFirst20, fidelityLog, shadowLog, 50,
                        catalogue, analysisAdapter, sqlPlanner, bundle, MAPPER);

        assertFalse(cases.isEmpty(), "expected benchmark cases from target artifacts");

        int exercised = 0;
        for (SqlFidelityBenchmarkRunner.BenchmarkCase c : cases) {
            CanonicalQueryModel canonical = adapter.adapt(c.plan());
            if (!validator.validate(canonical, catalogue).valid()) {
                continue;
            }
            runInvariantForPlan(c.index(), c.question(), c.plan());
            exercised++;
        }
        assertTrue(exercised > 0, "at least one stored benchmark case must be canonical-valid");
    }

    @Test
    void groundingVerifierDetectsInventedNumbers() {
        List<Map<String, Object>> rows = List.of(Map.of("total_revenue", 1_050_000.0));
        AnswerSynthesisOutput invented = new AnswerSynthesisOutput(
                "Total revenue reached $9,999,999 across all regions.",
                List.of("North contributed $5,000,000"),
                "high",
                "BAR",
                "GROUPED",
                List.of());

        List<String> violations = AnswerGroundingVerifier.ungroundedNumbers(
                invented.executiveSummary(),
                invented.keyFindings(),
                rows);

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.contains("9999999") || v.contains("5000000")));
    }

  private void runInvariantForPlan(int caseIndex, String question, StructuredSemanticPlan plan)
            throws Exception {
        var bundle = MetricResolutionTestSupport.oilBundle();
        var catalogue = SemanticCatalogueFactory.catalogueFrom(null, bundle);

        CanonicalQueryModel canonical = adapter.adapt(plan);
        assertTrue(validator.validate(canonical, catalogue).valid(),
                "benchmark case must be canonically valid: " + question);

        QuerySpec spec = renderer.render(canonical, catalogue.qualifiedTableName());
        assertEquals("canonical__sql", spec.key());

        List<Map<String, Object>> canonicalRows = WarehouseRowFixtures.rowsFor(canonical, caseIndex);
        assertFalse(canonicalRows.isEmpty(), "fixture rows required for " + question);

        List<Map<String, Object>> decoyRows = List.of(
                Map.of("LEGACY_DECOY_METRIC", 88_888_888.88, "region", "Decoyland"));

        ComputationResultSet pollutedResults = new ComputationResultSet(
                UUID.randomUUID(),
                List.of(
                        new QueryResult("canonical__sql", canonicalRows, 1),
                        new QueryResult("metric_pack__legacy_total", decoyRows, 1)),
                Map.of());

        AnswerSynthesisProperties props = new AnswerSynthesisProperties();
        props.setMode(AnswerSynthesisProperties.Mode.gpt);
        AnswerSynthesisInputBuilder inputBuilder = new AnswerSynthesisInputBuilder(props);

        AnswerSynthesisInput input = inputBuilder.build(
                question,
                List.of(spec),
                pollutedResults,
                legacyDecoyResolution(),
                legacyDecoyInvestigationPlan(),
                0.99,
                null,
                UUID.randomUUID(),
                canonical);

        assertEquals(canonicalRows, input.warehouseRows(),
                "synthesis input must include only canonical-keyed warehouse rows");
        assertEquals(canonical.measure().column(), input.metric().column());
        if (canonical.partition() != null && canonical.partition().column() != null) {
            assertEquals(canonical.partition().column(), input.dimension().column());
        }
        assertFalse(input.metric().column().contains("LEGACY"));
        assertFalse(input.dimension().column() != null
                && input.dimension().column().contains("LEGACY"));

        String userPrompt = AnswerSynthesisPrompt.userPrompt(input, MAPPER);
        SynthesisPromptPurityChecker.assertCanonicalUserPrompt(userPrompt, input, MAPPER);
        SynthesisPromptPurityChecker.assertNoDecoyNumbers(userPrompt, decoyRows);

        GptStructuredCompletionClient client = mock(GptStructuredCompletionClient.class);
        when(client.completeStructured(anyString(), anyString(), any(), eq("warehouse_answer_synthesis")))
                .thenReturn(faithfulSynthesisJson(canonicalRows));

        GptAnswerSynthesizer gpt = new GptAnswerSynthesizer(client, MAPPER);
        AnswerSynthesizer synthesizer = new AnswerSynthesizer(props, gpt);

        AnswerSynthesisOutput output = synthesizer.synthesize(input).orElseThrow();
        AnswerGroundingVerifier.assertFullyGrounded(output, canonicalRows);
    }

    private static String faithfulSynthesisJson(List<Map<String, Object>> rows) {
        StringBuilder summary = new StringBuilder();
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (summary.length() > 0) {
                    summary.append(' ');
                }
                summary.append(e.getKey()).append(' ').append(e.getValue());
            }
        }
        return """
                {
                  "executiveSummary": "%s",
                  "keyFindings": [],
                  "confidenceExplanation": "Grounded in warehouse rows.",
                  "suggestedVisualization": "TABLE",
                  "answerType": "GROUPED"
                }
                """.formatted(summary.toString().replace("\"", "'"));
    }

    private static MetricResolution legacyDecoyResolution() {
        return new MetricResolution(
                "LEGACY_DECOY_METRIC",
                "Legacy Revenue",
                null,
                null,
                "LEGACY_DECOY_DIMENSION",
                "Legacy Region",
                "LEGACY_DECOY_DIMENSION",
                0.99,
                false,
                null);
    }

    private static InvestigationPlan legacyDecoyInvestigationPlan() {
        MetricDecompositionBinding binding = new MetricDecompositionBinding(
                "LEGACY_DECOY_METRIC",
                "Legacy Revenue",
                AggregationType.SUM,
                "LEGACY_DECOY_DIMENSION",
                "Legacy Region",
                null);
        AnalyticalReasoningPlan reasoning = new AnalyticalReasoningPlan(
                AnalyticalIntentType.DISTRIBUTION,
                "LEGACY_DECOY_METRIC",
                "LEGACY_DECOY_DIMENSION",
                "vs_peer_average",
                null,
                List.of(),
                null,
                binding,
                "legacy decoy plan");
        return new InvestigationPlan(
                "legacy-decoy",
                AnalyticalIntentType.DISTRIBUTION,
                PlanDepth.STANDARD,
                List.of(),
                List.of(),
                null,
                List.of("LEGACY_DECOY_DIMENSION"),
                "decoy",
                reasoning,
                null,
                null);
    }

    private static StructuredSemanticPlan plan(
            String intent,
            String metric,
            String secondary,
            List<String> dimensions,
            StructuredSemanticPlan.SemanticAggregations aggregations,
            StructuredSemanticPlan.SemanticOrdering ordering,
            Integer limit,
            String relationship,
            String timeGrain
    ) {
        return new StructuredSemanticPlan(
                intent, metric, secondary, dimensions, List.of(),
                aggregations, ordering, limit, relationship, timeGrain,
                0.9, "benchmark", List.of());
    }

    private static StructuredSemanticPlan.SemanticAggregations agg(String primary, String secondary) {
        return new StructuredSemanticPlan.SemanticAggregations(primary, secondary);
    }

    private static StructuredSemanticPlan.SemanticOrdering order(String column, String direction) {
        return new StructuredSemanticPlan.SemanticOrdering(column, direction);
    }
}
