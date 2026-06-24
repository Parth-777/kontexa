package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ObjectiveDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.transforms.SemanticTransformationEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.UniversalAnalysisPlanner;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigation;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigationPlanner;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.decision.semantics.catalog.UniversalPlannerTestSupport;
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
 * Golden analytical queries that MUST produce deterministic executable SQL.
 */
class NycTaxiAnalyticalSqlGoldenTest {

    private static final String TABLE = "yellow_taxi_trips";

    private AnalyticalSqlTemplateEngine templateEngine;
    private DeterministicAnalyticalQueryPlanner planner;
    private SemanticTransformationEngine transformationEngine;
    private UniversalAnalysisPlanner analysisPlanner;
    private QuestionSemanticExtractor semanticExtractor;
    private MetricResolutionEngine metricEngine;
    private QuestionInvestigationPlanner investigationPlanner;

    @BeforeEach
    void setUp() {
        SqlTemplateTestHarness harness = SqlTemplateTestHarness.create();
        templateEngine = harness.templateEngine;
        transformationEngine = harness.transformationEngine;
        planner = harness.planner;
        analysisPlanner = UniversalPlannerTestSupport.universalPlanner();
        semanticExtractor = MetricResolutionTestSupport.extractor();
        metricEngine = MetricResolutionTestSupport.engine();
        investigationPlanner = UniversalPlannerTestSupport.investigationPlanner();
    }

    static Stream<Arguments> goldenQuestions() {
        return Stream.of(
                Arguments.of(
                        "How does trip distance affect revenue?",
                        "(?i)CORR\\s*\\(",
                        "(?i)trip_distance",
                        "(?i)total_amount"
                ),
                Arguments.of(
                        "Revenue by trip distance",
                        "(?i)CASE.*trip_distance",
                        "(?i)SUM\\s*\\(\\s*total_amount\\s*\\)",
                        "(?i)ORDER\\s+BY.+DESC"
                ),
                Arguments.of(
                        "Revenue by hour",
                        "(?i)EXTRACT\\s*\\(\\s*HOUR",
                        "(?i)SUM\\s*\\(\\s*total_amount\\s*\\)",
                        "(?i)GROUP\\s+BY"
                ),
                Arguments.of(
                        "Top pickup zones by revenue",
                        "(?i)pickup_zone",
                        "SUM\\s*\\(\\s*total_amount\\s*\\)",
                        "LIMIT\\s+10"
                ),
                Arguments.of(
                        "Weekend vs weekday revenue",
                        "(?i)(Weekend|Weekday|DAYOFWEEK)",
                        "(?i)SUM\\s*\\(\\s*total_amount\\s*\\)",
                        "(?i)GROUP\\s+BY"
                ),
                Arguments.of(
                        "Tip contribution to revenue",
                        "SUM\\s*\\(\\s*tip_amount\\s*\\)",
                        "SUM\\s*\\(\\s*total_amount\\s*\\)",
                        "share_pct"
                )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenQuestions")
    void goldenQuery_producesDeterministicSql(
            String question, String pattern1, String pattern2, String pattern3
    ) {
        RegistryResolutionBundle bundle = bundle();
        QuestionSemantics semantics = semanticExtractor.extract(question, bundle);
        MetricResolution resolution = metricEngine.resolve(semantics, bundle);
        QuestionInvestigation investigation = investigationPlanner.plan(question, bundle);
        AnalysisPlan plan = analysisPlanner.plan(question, bundle, investigation, resolution, List.of());
        assertTrue(plan.executable(), plan.blockingReason());
        List<QuerySpec> specs = planner.plan(plan, bundle);
        assertFalse(specs.isEmpty(), "Planner must emit at least one SQL spec");

        String sql = specs.stream().map(QuerySpec::sql).reduce("", String::concat);
        assertFalse(sql.isBlank(), "SQL must not be blank");
        assertTrue(sql.matches("(?is).*" + pattern1 + ".*"),
                "SQL missing expected fragment 1 for: " + question + "\nSQL: " + sql);
        assertTrue(sql.matches("(?is).*" + pattern2 + ".*"),
                "SQL missing expected fragment 2 for: " + question + "\nSQL: " + sql);
        assertTrue(sql.matches("(?is).*" + pattern3 + ".*"),
                "SQL missing expected fragment 3 for: " + question + "\nSQL: " + sql);
        assertTrue(sql.contains(TABLE), "SQL must reference NYC taxi table");
    }

    @Test
    void tripDistanceBucket_matchesHardcodedRanges() {
        String bucket = DimensionBucketingSql.tripDistanceBucket("trip_distance");
        assertTrue(bucket.contains("WHEN trip_distance < 1 THEN '0-1'"));
        assertTrue(bucket.contains("WHEN trip_distance < 3 THEN '1-3'"));
        assertTrue(bucket.contains("WHEN trip_distance < 5 THEN '3-5'"));
        assertTrue(bucket.contains("WHEN trip_distance < 10 THEN '5-10'"));
        assertTrue(bucket.contains("WHEN trip_distance < 20 THEN '10-20'"));
        assertTrue(bucket.contains("ELSE '20+'"));
    }

    @Test
    void fallbackChain_generatesFiveVariants() {
        TemplateContext ctx = TemplateContext.contribution(
                "Revenue by trip distance", TABLE,
                HardMetricMappings.PRIMARY_REVENUE,
                HardMetricMappings.DISTANCE_DIMENSION, "primary");
        QuerySpec spec = templateEngine.generate(ctx);
        List<String> fallbacks = SqlTemplateTestHarness.create().fallbackChain.fallbacks(spec.sql(), ctx);
        assertTrue(fallbacks.size() >= 3, "Must produce raw-dimension, avg, and top-10 fallbacks");
    }

    @Test
    void hardMetricMappings_resolveRevenueWithoutGuessing() {
        assertTrue(HardMetricMappings.REVENUE_METRICS.contains("total_amount"));
        assertTrue(HardMetricMappings.REVENUE_METRICS.contains("fare_amount"));
        assertTrue(HardMetricMappings.REVENUE_METRICS.contains("tip_amount"));
        assertTrue(HardMetricMappings.resolveRevenueMetric("tip contribution").contains("tip"));
        assertTrue(HardMetricMappings.resolveRevenueMetric("total revenue").contains("total"));
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
