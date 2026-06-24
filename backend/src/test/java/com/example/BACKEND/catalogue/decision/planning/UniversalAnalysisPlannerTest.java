package com.example.BACKEND.catalogue.decision.planning;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.catalogue.decision.investigation.AnalyticalInvestigationIntent;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigation;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigationPlanner;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.decision.semantics.catalog.UniversalPlannerTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class UniversalAnalysisPlannerTest {

    private UniversalAnalysisPlanner planner;
    private QuestionSemanticExtractor extractor;
    private MetricResolutionEngine metricEngine;
    private QuestionInvestigationPlanner investigationPlanner;
    private DeterministicAnalyticalQueryPlanner sqlPlanner;

    @BeforeEach
    void setUp() {
        planner = UniversalPlannerTestSupport.universalPlanner();
        extractor = MetricResolutionTestSupport.extractor();
        metricEngine = MetricResolutionTestSupport.engine();
        investigationPlanner = UniversalPlannerTestSupport.investigationPlanner();
        sqlPlanner = SqlTemplateTestHarness.create().planner;
    }

    static Stream<Arguments> oilRelationshipQuestions() {
        return Stream.of(
                Arguments.of("How does downtime affect profitability?", "profit_margin", "downtime_hours"),
                Arguments.of("How do maintenance costs impact profit?", "profit_margin", "maintenance_cost"),
                Arguments.of("Does carbon emission correlate with profit margin?", "profit_margin", "carbon_emission")
        );
    }

    @ParameterizedTest
    @MethodSource("oilRelationshipQuestions")
    void oilTenant_relationshipQuestions_produceExecutablePlanAndSql(
            String question, String expectedMetric, String expectedSource
    ) {
        RegistryResolutionBundle bundle = MetricResolutionTestSupport.oilBundle();
        QuestionSemantics semantics = extractor.extract(question, bundle);
        MetricResolution resolution = metricEngine.resolve(semantics, bundle);
        QuestionInvestigation investigation = investigationPlanner.plan(question, bundle);

        AnalysisPlan plan = planner.plan(question, bundle, investigation, resolution, List.of());

        assertTrue(plan.executable(), plan.blockingReason());
        assertEquals(AnalysisIntent.RELATIONSHIP, plan.intent());
        assertEquals(expectedMetric, plan.primaryMetric());
        assertEquals(expectedSource, plan.relationshipVariable());
        assertNull(plan.dimension());

        List<QuerySpec> specs = sqlPlanner.plan(plan, bundle);
        assertFalse(specs.isEmpty());
        assertTrue(specs.getFirst().sql().contains("CORR("));
        assertTrue(specs.getFirst().sql().contains(expectedSource));
        assertTrue(specs.getFirst().sql().contains(expectedMetric));
    }

    @Test
    void unseenRetailDataset_rankingQuestion_producesGroupedSql() {
        String table = "store_sales";
        RegistryResolutionBundle bundle = new RegistryResolutionBundle(
                List.of(new EntityDescriptor("retail", table, List.of("sale_id"), List.of("retail"))),
                List.of(
                        new MetricDescriptor(table + ".revenue", "revenue", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".order_count", "order_count", "FLOAT", "SUM", null)
                ),
                List.of(
                        new DimensionDescriptor(table + ".store_region", "store_region", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".product_line", "product_line", "CATEGORICAL")
                ),
                null);

        String question = "Which store region generates the most revenue?";
        QuestionSemantics semantics = extractor.extract(question, bundle);
        MetricResolution resolution = metricEngine.resolve(semantics, bundle);
        QuestionInvestigation investigation = investigationPlanner.plan(question, bundle);

        AnalysisPlan plan = planner.plan(question, bundle, investigation, resolution, List.of());

        assertTrue(plan.executable(), plan.blockingReason());
        assertEquals(table, plan.tableRef());
        assertEquals(AnalysisIntent.RANKING, plan.intent());
        assertNotNull(plan.primaryMetric());
        assertNotNull(plan.dimension());

        List<QuerySpec> specs = sqlPlanner.plan(plan, bundle);
        assertFalse(specs.isEmpty());
        assertTrue(specs.getFirst().sql().contains("GROUP BY"));
        assertTrue(specs.getFirst().sql().contains(plan.dimension())
                || specs.getFirst().sql().contains(plan.groupingAlias()));
    }

    @Test
    void unseenRetailDataset_relationshipQuestion_producesCorrelationSql() {
        String table = "store_sales";
        RegistryResolutionBundle bundle = new RegistryResolutionBundle(
                List.of(new EntityDescriptor("retail", table, List.of("sale_id"), List.of("retail"))),
                List.of(
                        new MetricDescriptor(table + ".revenue", "revenue", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".order_count", "order_count", "FLOAT", "SUM", null)
                ),
                List.of(
                        new DimensionDescriptor(table + ".store_region", "store_region", "CATEGORICAL")
                ),
                null);

        String question = "How does order count affect revenue?";
        QuestionSemantics semantics = extractor.extract(question, bundle);
        MetricResolution resolution = metricEngine.resolve(semantics, bundle);
        QuestionInvestigation investigation = investigationPlanner.plan(question, bundle);

        AnalysisPlan plan = planner.plan(question, bundle, investigation, resolution, List.of());

        assertTrue(plan.executable(), plan.blockingReason());
        assertEquals(AnalysisIntent.RELATIONSHIP, plan.intent());
        assertEquals(AnalyticalInvestigationIntent.RELATIONSHIP, investigation.extraction().intent());

        List<QuerySpec> specs = sqlPlanner.plan(plan, bundle);
        assertFalse(specs.isEmpty());
        assertTrue(specs.getFirst().sql().contains("CORR("));
    }

    @Test
    void emptyBundle_producesBlockedPlan() {
        AnalysisPlan plan = planner.plan("What is revenue?", null);
        assertFalse(plan.executable());
        assertTrue(plan.blockingReason().contains("schema"));
    }
}
