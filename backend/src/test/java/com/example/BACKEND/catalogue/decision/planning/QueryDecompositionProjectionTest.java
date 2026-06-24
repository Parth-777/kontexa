package com.example.BACKEND.catalogue.decision.planning;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticRegistry;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.decision.semantics.catalog.UniversalPlannerTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryDecompositionProjectionTest {

    private QueryDecompositionEngine engine;
    private UniversalAnalysisPlanner universalPlanner;

    @BeforeEach
    void setUp() {
        engine = new QueryDecompositionEngine(
                new DomainAnalyticalDefaults(),
                new MetricSemanticRegistry(new DomainAnalyticalDefaults()));
        universalPlanner = UniversalPlannerTestSupport.universalPlanner();
    }

    @Test
    void studentRanking_projectsMetricDimensionIntentFromAnalysisPlan() {
        RegistryResolutionBundle bundle = studentBundle();
        String question = "Which subjects have the highest exam performance?";

        AnalysisPlan plan = buildPlan(question, bundle);
        AnalyticalReasoningPlan reasoning = engine.decomposeFromAnalysisPlan(plan);

        assertEquals(AnalysisIntent.RANKING, plan.intent());
        assertEquals(AnalyticalIntentType.RANKING, reasoning.intent());
        assertEquals("exam_score", reasoning.metricBinding().metricColumn());
        assertEquals("subject", reasoning.metricBinding().groupingColumn());
        assertEquals(AggregationType.SUM, reasoning.metricBinding().aggregation());
    }

    @Test
    void relationshipIntent_projectsAggregationFromIntent() {
        RegistryResolutionBundle bundle = taxiBundle();
        String question = "How does trip distance affect revenue?";
        AnalysisPlan plan = buildPlan(question, bundle);
        if (!plan.executable()) {
            return;
        }
        AnalyticalReasoningPlan reasoning = engine.decomposeFromAnalysisPlan(plan);
        assertEquals(AnalysisIntent.RELATIONSHIP, plan.intent());
        assertEquals(AggregationType.AVG, reasoning.metricBinding().aggregation());
    }

    @Test
    void nullAnalysisPlan_failsProjection() {
        assertThrows(AnalysisPlanProjectionException.class,
                () -> engine.decomposeFromAnalysisPlan(null));
    }

    @Test
    void blockedPlan_missingMetric_failsProjection() {
        AnalysisPlan blocked = AnalysisPlan.blocked("vague", "Primary metric unresolved from schema");
        AnalysisPlanProjectionException ex = assertThrows(AnalysisPlanProjectionException.class,
                () -> engine.decomposeFromAnalysisPlan(blocked));
        assertEquals(true, ex.getMessage().contains("primaryMetric insufficient"));
    }

    private AnalysisPlan buildPlan(String question, RegistryResolutionBundle bundle) {
        QuestionSemanticExtractor extractor = MetricResolutionTestSupport.extractor();
        MetricResolutionEngine metricEngine = MetricResolutionTestSupport.engine();
        var investigation = UniversalPlannerTestSupport.investigationPlanner().plan(question, bundle);
        var semantics = extractor.extract(question, bundle);
        MetricResolution resolution = metricEngine.resolve(semantics, bundle);
        return universalPlanner.plan(question, bundle, investigation, resolution, List.of());
    }

    private static RegistryResolutionBundle studentBundle() {
        String table = "student_records";
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor("students", table, List.of("id"), List.of("edu"))),
                List.of(new MetricDescriptor(table + ".exam_score", "exam_score", "FLOAT", "AVG", null)),
                List.of(new DimensionDescriptor(table + ".subject", "subject", "CATEGORICAL")),
                null);
    }

    private static RegistryResolutionBundle taxiBundle() {
        String table = "yellow_taxi_trips";
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor("taxi", table, List.of("id"), List.of("transport"))),
                List.of(
                        new MetricDescriptor(table + ".total_amount", "total_amount", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".trip_distance", "trip_distance", "FLOAT", "SUM", null)),
                List.of(
                        new DimensionDescriptor(table + ".pickup_zone", "pickup_zone", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".trip_distance", "trip_distance", "NUMERIC")),
                null);
    }
}
