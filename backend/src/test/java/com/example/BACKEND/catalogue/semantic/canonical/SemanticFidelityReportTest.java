package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.planning.AnalysisIntent;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.StructuredPlanProjection;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticDiscoveryDebug;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanToAnalysisPlanAdapter;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanValidationResult;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SemanticFidelityReportTest {

    private final CanonicalQueryModelAdapter canonicalAdapter = new CanonicalQueryModelAdapter();
    private final SemanticPlanToAnalysisPlanAdapter analysisAdapter = new SemanticPlanToAnalysisPlanAdapter();

    @Test
    void detectsAggregationMutationInSql() {
        var plan = new StructuredSemanticPlan(
                "COMPARISON", "profit_margin", null,
                List.of("region"), List.of(),
                new StructuredSemanticPlan.SemanticAggregations("AVG", null),
                null, null, null, null,
                0.9, "avg margin", List.of());

        CanonicalQueryModel canonical = canonicalAdapter.adapt(plan);
        AnalysisPlan analysisPlan = analysisAdapter.toAnalysisPlan(
                "q", "oil", plan, SemanticPlanValidationResult.ok());
        List<QuerySpec> specs = List.of(new QuerySpec(
                "main",
                "SELECT region, SUM(profit_margin) AS metric_value FROM oil GROUP BY region",
                Map.of()));

        SemanticFidelityReport.Result report = SemanticFidelityReport.compare(
                canonical, analysisPlan, specs);

        assertTrue(report.measurePreserved());
        assertFalse(report.aggregationPreserved());
        assertFalse(report.allPreserved());
        assertTrue(report.mutations().stream().anyMatch(m -> m.contains("aggregation")));
    }

    @Test
    void detectsLimitMutationInSql() {
        var plan = new StructuredSemanticPlan(
                "RANKING", "total_revenue", null,
                List.of("region"), List.of(),
                new StructuredSemanticPlan.SemanticAggregations("SUM", null),
                new StructuredSemanticPlan.SemanticOrdering("total_revenue", "DESC"),
                1, null, null,
                0.9, "top one", List.of());

        CanonicalQueryModel canonical = canonicalAdapter.adapt(plan);
        StructuredPlanProjection projection = new StructuredPlanProjection(
                List.of("region"), "SUM", null, "total_revenue", "DESC", 1, null);
        AnalysisPlan analysisPlan = new AnalysisPlan(
                "q", "oil", AnalysisIntent.RANKING,
                "total_revenue", "total revenue",
                "region", "region", "region",
                null, null, null, null,
                true, List.of(),
                SemanticDiscoveryDebug.empty(null),
                List.of(),
                projection);
        List<QuerySpec> specs = List.of(new QuerySpec(
                "main",
                "SELECT region, SUM(total_revenue) AS metric_value FROM oil GROUP BY region ORDER BY 2 DESC LIMIT 10",
                Map.of()));

        SemanticFidelityReport.Result report = SemanticFidelityReport.compare(
                canonical, analysisPlan, specs);

        assertTrue(report.measurePreserved());
        assertTrue(report.partitionPreserved());
        assertTrue(report.orderingPreserved());
        assertFalse(report.limitPreserved());
    }

    @Test
    void detectsRelationshipOperandSwap() {
        var plan = new StructuredSemanticPlan(
                "RELATIONSHIP", "carbon_emission_tons", "profit_margin",
                List.of(), List.of(),
                new StructuredSemanticPlan.SemanticAggregations(null, null),
                null, null, "carbon_emission_tons", null,
                0.9, "correlation", List.of());

        CanonicalQueryModel canonical = canonicalAdapter.adapt(plan);
        AnalysisPlan analysisPlan = analysisAdapter.toAnalysisPlan(
                "q", "oil", plan, SemanticPlanValidationResult.ok());

        SemanticFidelityReport.Result report = SemanticFidelityReport.compare(
                canonical, analysisPlan, List.of());

        assertTrue(report.measurePreserved());
        assertFalse(report.relationshipOperandsPreserved());
        assertTrue(report.mutations().stream().anyMatch(m -> m.contains("relationshipOperands")));
    }
}
