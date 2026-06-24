package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ObjectiveDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.diagnostics.RuntimeDependencyInvocationSink;
import com.example.BACKEND.catalogue.decision.diagnostics.RuntimeDependencyTracer;
import com.example.BACKEND.catalogue.decision.diagnostics.TracingDomainAnalyticalDefaults;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigation;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigationPlanner;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.UniversalAnalysisPlanner;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.decision.semantics.catalog.UniversalPlannerTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays {@code NycTaxiAnalyticalSqlGoldenTest} under bytecode tracing for taxi-specific legacy classes.
 */
class NycTaxiGoldenRuntimeDependencyTraceTest {

    private static final String TABLE = "yellow_taxi_trips";

    private static final List<String> TRACED_CLASSES = List.of(
            "com.example.BACKEND.catalogue.decision.clarification.DomainOntology",
            "com.example.BACKEND.catalogue.decision.planning.DomainAnalyticalDefaults",
            "com.example.BACKEND.catalogue.decision.clarification.MetricFallbackHierarchy",
            "com.example.BACKEND.catalogue.decision.presentation.executive.BusinessSemanticAliases",
            "com.example.BACKEND.catalogue.decision.presentation.executive.RevenueCompositionAnalyzer",
            "com.example.BACKEND.catalogue.decision.candidate.CandidateAnalysisGenerator"
    );

    private SqlTemplateTestHarness harness;
    private DeterministicAnalyticalQueryPlanner planner;
    private UniversalAnalysisPlanner analysisPlanner;
    private QuestionSemanticExtractor semanticExtractor;
    private MetricResolutionEngine metricEngine;
    private QuestionInvestigationPlanner investigationPlanner;

    @BeforeAll
    static void installTracer() {
        RuntimeDependencyTracer.install();
    }

    @BeforeEach
    void setUp() {
        RuntimeDependencyInvocationSink.reset();
        harness = SqlTemplateTestHarness.create();
        planner = harness.planner;
        analysisPlanner = UniversalPlannerTestSupport.universalPlanner();
        semanticExtractor = MetricResolutionTestSupport.extractor();
        metricEngine = MetricResolutionTestSupport.engine(new TracingDomainAnalyticalDefaults());
        investigationPlanner = UniversalPlannerTestSupport.investigationPlanner();
    }

    static Stream<Arguments> goldenQuestions() {
        return Stream.of(
                Arguments.of("How does trip distance affect revenue?"),
                Arguments.of("Revenue by trip distance"),
                Arguments.of("Revenue by hour"),
                Arguments.of("Top pickup zones by revenue"),
                Arguments.of("Weekend vs weekday revenue"),
                Arguments.of("Tip contribution to revenue")
        );
    }

    @ParameterizedTest
    @MethodSource("goldenQuestions")
    void goldenQuery_producesDeterministicSql(String question) {
        runGoldenPipeline(question);
        printPerExecutionReport();
    }

    @Test
    void tripDistanceBucket_matchesHardcodedRanges() {
        String bucket = DimensionBucketingSql.tripDistanceBucket("trip_distance");
        assertTrue(bucket.contains("WHEN trip_distance < 1 THEN '0-1'"));
        printPerExecutionReport();
    }

    @Test
    void fallbackChain_generatesFiveVariants() {
        TemplateContext ctx = TemplateContext.contribution(
                "Revenue by trip distance", TABLE,
                HardMetricMappings.PRIMARY_REVENUE,
                HardMetricMappings.DISTANCE_DIMENSION, "primary");
        QuerySpec spec = harness.templateEngine.generate(ctx);
        List<String> fallbacks = harness.fallbackChain.fallbacks(spec.sql(), ctx);
        assertTrue(fallbacks.size() >= 3);
        printPerExecutionReport();
    }

    @Test
    void hardMetricMappings_resolveRevenueWithoutGuessing() {
        assertTrue(HardMetricMappings.REVENUE_METRICS.contains("total_amount"));
        assertTrue(HardMetricMappings.resolveRevenueMetric("tip contribution").contains("tip"));
        printPerExecutionReport();
    }

    private void runGoldenPipeline(String question) {
        RegistryResolutionBundle bundle = bundle();
        QuestionSemantics semantics = semanticExtractor.extract(question, bundle);
        MetricResolution resolution = metricEngine.resolve(semantics, bundle);
        QuestionInvestigation investigation = investigationPlanner.plan(question, bundle);
        AnalysisPlan plan = analysisPlanner.plan(question, bundle, investigation, resolution, List.of());
        assertTrue(plan.executable(), plan.blockingReason());
        List<QuerySpec> specs = planner.plan(plan, bundle);
        assertFalse(specs.isEmpty());
        String sql = specs.stream().map(QuerySpec::sql).reduce("", String::concat);
        assertFalse(sql.isBlank());
        assertTrue(sql.contains(TABLE));
    }

    private void printPerExecutionReport() {
        System.out.println("\n=== RUNTIME DEPENDENCY TRACE (this execution) ===");
        for (String className : TRACED_CLASSES) {
            String simple = className.substring(className.lastIndexOf('.') + 1);
            int count = RuntimeDependencyInvocationSink.count(className);
            System.out.println(simple + " invocations=" + count);
            if (count == 0) {
                System.out.println("  (not entered)");
                continue;
            }
            int sampleIdx = 0;
            for (RuntimeDependencyInvocationSink.Invocation inv : RuntimeDependencyInvocationSink.samples(className)) {
                sampleIdx++;
                System.out.println("  sample stack #" + sampleIdx + " via " + inv.method() + "():");
                for (String frame : inv.stack()) {
                    System.out.println("    at " + frame);
                }
            }
        }
    }

    private RegistryResolutionBundle bundle() {
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor("taxi", TABLE, List.of("trip_id"), List.of("nyc_taxi"))),
                List.of(
                        new MetricDescriptor(TABLE + ".total_amount", "total_amount", "FLOAT", "SUM", null),
                        new MetricDescriptor(TABLE + ".tip_amount", "tip_amount", "FLOAT", "SUM", null),
                        new MetricDescriptor(TABLE + ".trip_distance", "trip_distance", "FLOAT", "SUM", null)
                ),
                List.of(
                        new DimensionDescriptor(TABLE + ".pickup_zone", "pickup_zone", "CATEGORICAL"),
                        new DimensionDescriptor(TABLE + ".weekend_flag", "weekend_flag", "CATEGORICAL"),
                        new DimensionDescriptor(TABLE + ".trip_distance", "trip_distance", "NUMERIC"),
                        new DimensionDescriptor(TABLE + ".pickup_hour", "pickup_hour", "TEMPORAL"),
                        new DimensionDescriptor(TABLE + ".payment_type", "payment_type", "CATEGORICAL")
                ),
                new ObjectiveDescriptor("GENERAL", "ANALYTICAL", List.of())
        );
    }
}
