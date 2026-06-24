package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ObjectiveDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigation;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigationPlanner;
import com.example.BACKEND.catalogue.decision.planning.AnalysisIntent;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.UniversalAnalysisPlanner;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.decision.semantics.catalog.UniversalPlannerTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces production pipeline: semantics → metric resolution → analysis plan → SQL.
 */
class DeterministicAnalyticalQueryPlannerRegressionTest {

    private static final String QUESTION = "How does trip distance affect revenue?";

    private DeterministicAnalyticalQueryPlanner sqlPlanner;
    private UniversalAnalysisPlanner universalPlanner;
    private MetricResolutionEngine metricResolutionEngine;
    private QuestionSemanticExtractor semanticExtractor;
    private QuestionInvestigationPlanner investigationPlanner;

    @BeforeEach
    void setUp() {
        var harness = SqlTemplateTestHarness.create();
        sqlPlanner = harness.planner;
        universalPlanner = UniversalPlannerTestSupport.universalPlanner();
        semanticExtractor = MetricResolutionTestSupport.extractor();
        metricResolutionEngine = MetricResolutionTestSupport.engine();
        investigationPlanner = UniversalPlannerTestSupport.investigationPlanner();
    }

    @Test
    void relationshipQuestion_withAnalysisPlan_producesCorrelationSql() {
        RegistryResolutionBundle bundle = bundleWithMetricsAndDistance();
        QuestionSemantics semantics = semanticExtractor.extract(QUESTION, bundle);
        MetricResolution resolution = metricResolutionEngine.resolve(semantics, bundle);
        QuestionInvestigation investigation = investigationPlanner.plan(QUESTION, bundle);

        AnalysisPlan plan = universalPlanner.plan(
                QUESTION, bundle, investigation, resolution, List.of());
        assertTrue(plan.executable(), plan.blockingReason());
        assertEquals(AnalysisIntent.RELATIONSHIP, plan.intent());

        List<QuerySpec> specs = sqlPlanner.plan(plan, bundle);
        assertFalse(specs.isEmpty());
        assertTrue(specs.getFirst().sql().contains("CORR("));
    }

    @Test
    void relationshipQuestion_withoutDimensionInRegistry_stillProducesSql() {
        RegistryResolutionBundle bundle = bundleWithMetricsAndDistance();
        QuestionSemantics semantics = semanticExtractor.extract(QUESTION, bundle);
        MetricResolution resolution = metricResolutionEngine.resolve(semantics, bundle);
        QuestionInvestigation investigation = investigationPlanner.plan(QUESTION, bundle);

        AnalysisPlan plan = universalPlanner.plan(
                QUESTION, bundle, investigation, resolution, List.of());
        List<QuerySpec> specs = sqlPlanner.plan(plan, bundle);

        assertFalse(specs.isEmpty(), "Relationship plans must produce SQL without grouping dimension");
        assertFalse(specs.getFirst().sql().isBlank());
    }

    private RegistryResolutionBundle bundleWithMetricsAndDistance() {
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor("taxi", "yellow_taxi_trips", List.of("trip_id"), List.of("nyc_taxi"))),
                List.of(
                        new MetricDescriptor("total_amount", "SUM({col})", "currency", "SUM", "USD"),
                        new MetricDescriptor("trip_distance", "SUM({col})", "numeric", "SUM", null)
                ),
                List.of(),
                new ObjectiveDescriptor("GENERAL", "ANALYTICAL", List.of())
        );
    }
}
