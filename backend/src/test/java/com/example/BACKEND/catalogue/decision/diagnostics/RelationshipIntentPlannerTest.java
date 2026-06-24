package com.example.BACKEND.catalogue.decision.diagnostics;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.IntentResolution;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.investigation.*;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.reasoning.AnalyticalReasoningPlanner;
import com.example.BACKEND.catalogue.decision.reasoning.QuestionDrivenReasoningPlan;
import com.example.BACKEND.catalogue.decision.planning.UniversalAnalysisPlanner;
import com.example.BACKEND.catalogue.decision.semantics.catalog.UniversalPlannerTestSupport;
import com.example.BACKEND.catalogue.decision.semantics.*;
import com.example.BACKEND.catalogue.decision.semantics.catalog.*;
import com.example.BACKEND.catalogue.decision.presentation.VisualizationStrategyEngine;
import com.example.BACKEND.catalogue.decision.transforms.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RelationshipIntentPlannerTest {

    private static final DeterministicAnalyticalQueryPlanner SQL_PLANNER =
            SqlTemplateTestHarness.create().planner;
    private static final UniversalAnalysisPlanner ANALYSIS_PLANNER =
            UniversalPlannerTestSupport.universalPlanner();

    static Stream<Arguments> relationshipQuestions() {
        return Stream.of(
                Arguments.of("How does downtime affect profitability?", "profit_margin", "downtime_hours"),
                Arguments.of("How do maintenance costs impact profit?", "profit_margin", "maintenance_cost"),
                Arguments.of("Does carbon emission correlate with profit margin?", "profit_margin", "carbon_emission"),
                Arguments.of("How does carbon emission relate to profit margin?", "profit_margin", "carbon_emission")
        );
    }

    @ParameterizedTest
    @MethodSource("relationshipQuestions")
    void relationshipQuestions_resolveWithoutDimensionAndGenerateSql(
            String question, String expectedMetric, String expectedSource
    ) {
        RegistryResolutionBundle bundle = MetricResolutionTestSupport.oilBundle();
        QuestionSemanticExtractor extractor = MetricResolutionTestSupport.extractor();
        MetricResolutionEngine metricEngine = MetricResolutionTestSupport.engine();
        QuestionInvestigationPlanner investigationPlanner = buildInvestigationPlanner();

        QuestionSemantics semantics = extractor.extract(question, bundle);
        assertEquals(AnalyticalIntentType.RELATIONSHIP, semantics.intent());
        assertEquals(AnalyticalRelationship.METRIC_RELATIONSHIP, semantics.relationship());
        assertEquals(expectedMetric, semantics.primaryMetric());
        assertEquals(expectedSource, semantics.targetMetric());
        assertNull(semantics.dimension());

        MetricResolution resolution = metricEngine.resolve(semantics, bundle);
        assertTrue(resolution.isUsable());
        assertEquals(expectedMetric, resolution.primaryMetric());
        assertNull(resolution.dimension());
        assertEquals(expectedSource, resolution.relationshipVariable());
        assertTrue(resolution.isRelationshipAnalysis());

        QuestionInvestigation investigation = investigationPlanner.plan(question, bundle);
        assertTrue(investigation.executable());
        assertEquals(AnalyticalInvestigationIntent.RELATIONSHIP, investigation.extraction().intent());
        assertEquals(expectedSource, investigation.extraction().targetMetricKey());

        QuestionDrivenReasoningPlan plan = new AnalyticalReasoningPlanner(
                new VisualizationStrategyEngine(), new DerivedDimensionRegistry())
                .plan(semantics, resolution);
        AnalysisPlan analysisPlan = ANALYSIS_PLANNER.plan(
                question, bundle, investigation, resolution, List.of());
        assertTrue(analysisPlan.executable(), analysisPlan.blockingReason());
        List<QuerySpec> specs = SQL_PLANNER.plan(analysisPlan, bundle);

        assertFalse(specs.isEmpty(), "Relationship questions must produce SQL");
        assertTrue(specs.getFirst().sql().contains("CORR("));
        assertTrue(specs.getFirst().sql().contains(expectedSource));
        assertTrue(specs.getFirst().sql().contains(expectedMetric));
    }

    private static QuestionInvestigationPlanner buildInvestigationPlanner() {
        return UniversalPlannerTestSupport.investigationPlanner();
    }
}
