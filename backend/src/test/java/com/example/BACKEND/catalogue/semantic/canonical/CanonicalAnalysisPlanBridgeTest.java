package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.decision.planning.AnalysisIntent;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalAnalysisPlanBridgeTest {

    @Test
    void mapsCanonicalFieldsWithoutInference() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM"),
                new CanonicalQueryModel.PartitionSpec("region", null),
                List.of(),
                null,
                null,
                new CanonicalQueryModel.OrderSpec("total_revenue", "DESC"),
                5,
                new CanonicalQueryModel.PlannerMetadata(
                        "RANKING", 0.9, "test", List.of("region"), null, null));

        AnalysisPlan plan = CanonicalAnalysisPlanBridge.toAnalysisPlan(
                "Top regions", "oil_operations", model);

        assertTrue(plan.executable());
        assertEquals(AnalysisIntent.RANKING, plan.intent());
        assertEquals("total_revenue", plan.primaryMetric());
        assertEquals("region", plan.dimension());
        assertEquals("SUM", plan.structuredProjection().primaryAggregation());
        assertEquals("total_revenue", plan.structuredProjection().orderColumn());
        assertEquals("DESC", plan.structuredProjection().orderDirection());
        assertEquals(5, plan.structuredProjection().resultLimit());
    }

    @Test
    void copiesRelationshipFieldsWithoutOperandRewriting() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("profit_margin", "AVG"),
                null,
                List.of(),
                null,
                new CanonicalQueryModel.BivariateSpec("profit_margin", "downtime_hours", "CORR"),
                null,
                null,
                new CanonicalQueryModel.PlannerMetadata(
                        "RELATIONSHIP", 0.9, "test", List.of(), null, "downtime_hours"));

        AnalysisPlan plan = CanonicalAnalysisPlanBridge.toAnalysisPlan(
                "Correlation", "oil_operations", model);

        assertEquals(AnalysisIntent.RELATIONSHIP, plan.intent());
        assertEquals("profit_margin", plan.primaryMetric());
        assertEquals("downtime_hours", plan.relationshipVariable());
        assertNull(plan.dimension());
    }

    @Test
    void mapsExtendedPlannerIntentsToExecutionIntents() {
        CanonicalQueryModel growth = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM"),
                new CanonicalQueryModel.PartitionSpec("order_date", "month"),
                List.of(), null, null, null, null,
                new CanonicalQueryModel.PlannerMetadata("GROWTH", 0.9, "", List.of("order_date"), null, null));
        assertEquals(AnalysisIntent.TREND,
                CanonicalAnalysisPlanBridge.toAnalysisPlan("Growth", "t", growth).intent());

        CanonicalQueryModel pareto = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM"),
                new CanonicalQueryModel.PartitionSpec("region", null),
                List.of(), null, null,
                new CanonicalQueryModel.OrderSpec("total_revenue", "DESC"),
                null,
                new CanonicalQueryModel.PlannerMetadata("PARETO", 0.9, "", List.of("region"), null, null));
        assertEquals(AnalysisIntent.RANKING,
                CanonicalAnalysisPlanBridge.toAnalysisPlan("Pareto", "t", pareto).intent());
    }
}
