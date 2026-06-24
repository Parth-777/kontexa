package com.example.BACKEND.catalogue.decision.diagnostics;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.IntentResolution;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.UniversalAnalysisPlanner;
import com.example.BACKEND.catalogue.decision.semantics.catalog.UniversalPlannerTestSupport;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticRegistry;
import com.example.BACKEND.catalogue.decision.investigation.*;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentClassifier;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.planning.DomainAnalyticalDefaults;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.decision.presentation.VisualizationStrategyEngine;
import com.example.BACKEND.catalogue.decision.reasoning.AnalyticalReasoningPlanner;
import com.example.BACKEND.catalogue.decision.reasoning.QuestionDrivenReasoningPlan;
import com.example.BACKEND.catalogue.decision.semantic.QueryEntityResolver;
import com.example.BACKEND.catalogue.decision.semantic.SemanticDictionary;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.catalogue.decision.semantics.catalog.CatalogQuestionMatcher;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SchemaDrivenQuestionResolver;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticCatalogBuilder;
import com.example.BACKEND.catalogue.decision.transforms.DerivedDimensionRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Concrete execution-path tracer. Run:
 * mvn test -Dtest=PipelineFailurePathDiagnosticTest
 */
class PipelineFailurePathDiagnosticTest {

    private static final QuestionInvestigationPlanner INVESTIGATION = buildInvestigationPlanner();
    private static final MetricResolutionEngine METRIC_ENGINE = MetricResolutionTestSupport.engine();
    private static final AnalyticalReasoningPlanner REASONING = new AnalyticalReasoningPlanner(
            new VisualizationStrategyEngine(), new DerivedDimensionRegistry());
    private static final DeterministicAnalyticalQueryPlanner SQL_PLANNER =
            SqlTemplateTestHarness.create().planner;
    private static final UniversalAnalysisPlanner ANALYSIS_PLANNER =
            UniversalPlannerTestSupport.universalPlanner();
    private static final AnalyticalIntentClassifier INTENT_CLASSIFIER = new AnalyticalIntentClassifier();

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("Which oil field generates the highest profit?", "oil"),
                Arguments.of("How does downtime affect profitability?", "oil"),
                Arguments.of("What percentage of total revenue comes from airport rides?", "taxi"),
                Arguments.of("How does trip distance contribute to revenue?", "taxi")
        );
    }

    @ParameterizedTest
    @MethodSource("cases")
    void tracePipeline(String question, String dataset) {
        RegistryResolutionBundle bundle = "oil".equals(dataset) ? oilBundle() : taxiBundle();
        IntentResolution intent = new IntentResolution(
                UUID.randomUUID(), "test", question, "GENERAL_ANALYSIS", 0.9);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("QUESTION: " + question);
        System.out.println("DATASET:  " + dataset + " (" + bundle.entities().getFirst().tableRef() + ")");
        System.out.println("=".repeat(80));

        AnalyticalIntentType classified = INTENT_CLASSIFIER.classify(intent);
        System.out.println("\n[1] INTENT CLASSIFIER (AnalyticalIntentClassifier)");
        System.out.println("  input:  IntentResolution.question");
        System.out.println("  output: " + classified);
        System.out.println("  confidence: n/a (deterministic keyword classifier)");

        QuestionInvestigation inv = INVESTIGATION.plan(question, bundle);
        QuestionSemantics semantics = inv != null ? extractSemanticsFromInvestigation(question, bundle, inv) : null;
        MetricResolution metric = METRIC_ENGINE.resolve(semantics, bundle);
        QuestionDrivenReasoningPlan reasoning = REASONING.plan(semantics, metric);

        if (inv != null && inv.discovery() != null) {
            var d = inv.discovery();
            System.out.println("\n[2] SCHEMA DISCOVERY (SchemaDrivenQuestionResolver)");
            System.out.println("  input:  question + SemanticCatalog from bundle");
            System.out.println("  candidate_metrics=" + d.candidateMetrics());
            System.out.println("  candidate_dimensions=" + d.candidateDimensions());
            System.out.println("  metric_resolution=" + d.metricResolution());
            System.out.println("  dimension_resolution=" + d.dimensionResolution());
            System.out.println("  intent_resolution=" + d.intentResolution());
            System.out.println("  metric_match_score=" + d.metricMatchScore());
            System.out.println("  dimension_match_score=" + d.dimensionMatchScore());
        }

        System.out.println("\n[3] SEMANTIC EXTRACTOR + OVERLAY (QuestionSemanticExtractor + overlaySchema)");
        printSemantics(semantics);

        System.out.println("\n[4] METRIC RESOLUTION (MetricResolutionEngine)");
        printMetricResolution(metric);

        System.out.println("\n[5] INVESTIGATION (QuestionInvestigationPlanner)");
        if (inv != null) {
            System.out.println("  input:  question, bundle");
            System.out.println("  extraction.metricKey=" + inv.extraction().metricKey());
            System.out.println("  extraction.businessEntityKey=" + inv.extraction().businessEntityKey());
            System.out.println("  extraction.businessEntityPhrase=" + inv.extraction().businessEntityPhrase());
            System.out.println("  extraction.intent=" + inv.extraction().intent());
            System.out.println("  extraction.confidence=" + inv.extraction().confidence());
            System.out.println("  dimension.resolved=" + inv.dimension().resolved());
            System.out.println("  dimension.columnKey=" + inv.dimension().columnKey());
            System.out.println("  dimension.groupingAlias=" + inv.dimension().groupingAlias());
            System.out.println("  dimension.failureMessage=" + inv.dimension().failureMessage());
            System.out.println("  output: executable=" + inv.executable());
            System.out.println("  rejection: " + (inv.executable() ? "none" : inv.blockingReason()));
        }

        System.out.println("\n[6] REASONING PLAN (AnalyticalReasoningPlanner)");
        System.out.println("  input:  QuestionSemantics + MetricResolution");
        System.out.println("  resolution.isUsable=" + (metric != null && metric.isUsable()));
        System.out.println("  queryPlan steps=" + reasoning.queryPlan().size());
        for (var step : reasoning.queryPlan()) {
            System.out.println("    [" + step.key() + "] metric=" + step.metric()
                    + " dimension=" + step.dimension()
                    + " grouping=" + step.grouping()
                    + " sqlIntent=" + step.sqlIntent());
        }

        AnalysisPlan analysisPlan = ANALYSIS_PLANNER.plan(
                question, bundle, inv, metric, List.of());
        List<QuerySpec> specs = SQL_PLANNER.plan(analysisPlan, bundle);

        System.out.println("\n[7] SQL PLANNER (DeterministicAnalyticalQueryPlanner)");
        System.out.println("  input:  analysisPlan, bundle");
        System.out.println("  analysisPlan.executable=" + analysisPlan.executable());
        System.out.println("  analysisPlan.intent=" + analysisPlan.intent());
        System.out.println("  output: specs=" + specs.size());
        if (specs.isEmpty()) {
            System.out.println("  ABORT: " + abortReason(inv, metric, reasoning));
        } else {
            for (QuerySpec q : specs) {
                System.out.println("  --- spec: " + q.key() + " ---");
                System.out.println(q.sql());
            }
        }

        System.out.println("\n>>> FIRST SQL STOP GATE: " + firstStopGate(inv, metric, specs));
    }

    /** Re-run extraction path matching investigation planner internals. */
    private QuestionSemantics extractSemanticsFromInvestigation(
            String question, RegistryResolutionBundle bundle, QuestionInvestigation inv
    ) {
        QuestionSemanticExtractor ext = buildExtractor();
        SemanticCatalogBuilder cb = new SemanticCatalogBuilder();
        SchemaDrivenQuestionResolver sr = new SchemaDrivenQuestionResolver(new CatalogQuestionMatcher());
        var schema = sr.resolve(question, cb.build(bundle));
        QuestionSemantics raw = ext.extract(question, bundle);
        if (!schema.usable()) return raw;
        return new QuestionSemantics(
                raw.question(),
                schema.metricColumn() != null ? schema.metricColumn() : raw.primaryMetric(),
                schema.metricColumn() != null ? SemanticCatalogBuilder.humanize(schema.metricColumn()) : raw.primaryMetricLabel(),
                raw.targetMetric(), raw.targetMetricLabel(),
                schema.dimensionColumn() != null ? schema.dimensionColumn() : raw.dimension(),
                schema.dimensionColumn() != null ? SemanticCatalogBuilder.humanize(schema.dimensionColumn()) : raw.dimensionLabel(),
                schema.dimensionColumn() != null ? schema.dimensionColumn() : raw.grouping(),
                raw.intent(), raw.relationship(), raw.temporalReferences(),
                Math.max(raw.confidence(), 0.78), raw.extractedEntities());
    }

    private static String branch(MetricResolution metric, QuestionInvestigation inv) {
        if (inv != null && !inv.executable()) return "BLOCKED at gate (never enters planFromReasoning or legacy)";
        if (metric != null && metric.isUsable()) return "planFromReasoning";
        return "legacy (detectIntent + detectDimension + HardMetricMappings)";
    }

    private static String abortReason(
            QuestionInvestigation inv, MetricResolution metric, QuestionDrivenReasoningPlan reasoning
    ) {
        if (inv != null && !inv.executable()) {
            return "DeterministicAnalyticalQueryPlanner:78 — investigation.executable=false, returns List.of(). Reason: "
                    + inv.blockingReason();
        }
        if (metric != null && metric.isUsable()) {
            boolean allStepsNullDim = reasoning.queryPlan().stream()
                    .allMatch(s -> s.dimension() == null || s.dimension().isBlank());
            if (allStepsNullDim && (metric.dimension() == null || metric.dimension().isBlank())) {
                return "planFromReasoning:159-169 — all steps skipped (null dimension), fallback dimension also null";
            }
            return "planFromReasoning: steps skipped (authoritativeDimension null or SemanticQueryRewriter failed)";
        }
        return "legacy path: AnalyticalSqlTemplateEngine.detectDimension() returned null → no primary spec";
    }

    private static String firstStopGate(
            QuestionInvestigation inv, MetricResolution metric, List<QuerySpec> specs
    ) {
        if (specs.isEmpty() && inv != null && !inv.executable()) {
            return "QuestionInvestigationPlanner.executable=false "
                    + "→ DeterministicAnalyticalQueryPlanner returns empty list at line 78-81";
        }
        if (specs.isEmpty() && metric != null && metric.isUsable() && metric.dimension() == null) {
            return "MetricResolution.dimension=null with usable metric "
                    + "→ planFromReasoning skips all steps → empty specs at line 173-179";
        }
        if (specs.isEmpty()) {
            return "DeterministicAnalyticalQueryPlanner — empty spec map after planning";
        }
        return "NONE (SQL generated successfully)";
    }

    private static void printSemantics(QuestionSemantics s) {
        if (s == null) { System.out.println("  output: null"); return; }
        System.out.println("  primaryMetric=" + s.primaryMetric());
        System.out.println("  targetMetric=" + s.targetMetric());
        System.out.println("  dimension=" + s.dimension());
        System.out.println("  grouping=" + s.grouping());
        System.out.println("  intent=" + s.intent());
        System.out.println("  relationship=" + s.relationship());
        System.out.println("  confidence=" + s.confidence());
    }

    private static void printMetricResolution(MetricResolution r) {
        if (r == null) { System.out.println("  output: null"); return; }
        System.out.println("  primaryMetric=" + r.primaryMetric());
        System.out.println("  targetMetric=" + r.targetMetric());
        System.out.println("  dimension=" + r.dimension());
        System.out.println("  grouping=" + r.grouping());
        System.out.println("  confidence=" + r.confidence());
        System.out.println("  rejected=" + r.rejected());
        System.out.println("  rejectionReason=" + (r.rejectionReason() != null && !r.rejectionReason().isBlank()
                ? r.rejectionReason() : "none"));
        System.out.println("  isUsable=" + r.isUsable());
    }

    private static QuestionInvestigationPlanner buildInvestigationPlanner() {
        SemanticDictionary dictionary = new SemanticDictionary();
        CatalogQuestionMatcher matcher = new CatalogQuestionMatcher();
        SemanticCatalogBuilder catalogBuilder = new SemanticCatalogBuilder();
        SchemaDrivenQuestionResolver schemaResolver = new SchemaDrivenQuestionResolver(matcher);
        return new QuestionInvestigationPlanner(
                MetricResolutionTestSupport.extractor(),
                new QueryEntityResolver(dictionary),
                new DimensionResolver(matcher),
                new InvestigationStepPlanner(),
                catalogBuilder,
                schemaResolver,
                new com.example.BACKEND.catalogue.decision.semantics.RelationshipIntentDetector());
    }

    private static QuestionSemanticExtractor buildExtractor() {
        return MetricResolutionTestSupport.extractor();
    }

    private static RegistryResolutionBundle oilBundle() {
        String table = "oil_operations";
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor("oil", table, List.of("well_id"), List.of("oil_gas"))),
                List.of(
                        new MetricDescriptor(table + ".profit_margin", "profit_margin", "FLOAT", "AVG", null),
                        new MetricDescriptor(table + ".total_revenue", "total_revenue", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".downtime_hours", "downtime_hours", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".maintenance_cost", "maintenance_cost", "FLOAT", "SUM", null)
                ),
                List.of(
                        new DimensionDescriptor(table + ".oil_field", table + ".oil_field", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".region", table + ".region", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".facility_type", table + ".facility_type", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".product_type", table + ".product_type", "CATEGORICAL")
                ),
                null);
    }

    private static RegistryResolutionBundle taxiBundle() {
        String table = "yellow_taxi_trips";
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor("taxi", table, List.of("trip_id"), List.of("nyc_taxi"))),
                List.of(
                        new MetricDescriptor(table + ".total_amount", "total_amount", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".tip_amount", "tip_amount", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".trip_distance", "trip_distance", "FLOAT", "SUM", null)
                ),
                List.of(
                        new DimensionDescriptor(table + ".airport_flag", table + ".airport_flag", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".weekend_flag", table + ".weekend_flag", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".trip_distance", table + ".trip_distance", "NUMERIC"),
                        new DimensionDescriptor(table + ".pickup_zone", table + ".pickup_zone", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".payment_type", table + ".payment_type", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".pickup_hour", table + ".pickup_hour", "TEMPORAL")
                ),
                null);
    }
}
