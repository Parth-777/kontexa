package com.example.BACKEND.catalogue.semantic.phase2;



import com.example.BACKEND.catalogue.decision.planning.AnalysisIntent;

import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;

import org.junit.jupiter.api.Test;



import java.util.List;



import static org.junit.jupiter.api.Assertions.*;



class SemanticPlanToAnalysisPlanAdapterTest {



    private final SemanticPlanToAnalysisPlanAdapter adapter = new SemanticPlanToAnalysisPlanAdapter();



    @Test

    void mapsRelationshipPlan() {

        var plan = new StructuredSemanticPlan(

                "RELATIONSHIP", "profit_margin", null,

                List.of(), List.of(),

                new StructuredSemanticPlan.SemanticAggregations("AVG", null),

                null, null, "downtime_hours", null,

                0.9, "correlation", List.of());



        var analysis = adapter.toAnalysisPlan(

                "How does downtime affect profit?",

                "oil_operations",

                plan,

                SemanticPlanValidationResult.ok());



        assertTrue(analysis.executable());

        assertEquals(AnalysisIntent.RELATIONSHIP, analysis.intent());

        assertEquals("profit_margin", analysis.primaryMetric());

        assertEquals("downtime_hours", analysis.relationshipVariable());

        assertNull(analysis.dimension());

    }



    @Test

    void resolvesDuplicateRelationshipOperandFromSecondaryMetric() {

        var plan = new StructuredSemanticPlan(

                "RELATIONSHIP", "carbon_emission_tons", "profit_margin",

                List.of(), List.of(),

                new StructuredSemanticPlan.SemanticAggregations(null, null),

                null, null, "carbon_emission_tons", null,

                0.9, "correlation", List.of());



        var analysis = adapter.toAnalysisPlan(

                "Does carbon emission correlate with profit margin?",

                "oil",

                plan,

                SemanticPlanValidationResult.ok());



        assertTrue(analysis.executable());

        assertEquals("carbon_emission_tons", analysis.primaryMetric());

        assertEquals("profit_margin", analysis.relationshipVariable());

    }



    @Test

    void mapsRankingPlanWithDimensionAndProjection() {

        var plan = new StructuredSemanticPlan(

                "RANKING", "total_revenue", null,

                List.of("region"), List.of(),

                new StructuredSemanticPlan.SemanticAggregations("SUM", null),

                new StructuredSemanticPlan.SemanticOrdering("total_revenue", "DESC"),

                10, null, null,

                0.92, "top regions", List.of());



        var analysis = adapter.toAnalysisPlan(

                "Top regions by revenue",

                "oil_operations",

                plan,

                SemanticPlanValidationResult.ok());



        assertTrue(analysis.executable());

        assertEquals(AnalysisIntent.RANKING, analysis.intent());

        assertEquals("region", analysis.dimension());

        assertEquals("SUM", analysis.structuredProjection().primaryAggregation());

        assertEquals(10, analysis.structuredProjection().resultLimit());

        assertEquals("total_revenue", analysis.structuredProjection().orderColumn());

    }



    @Test

    void blocksWhenValidationFails() {

        var plan = new StructuredSemanticPlan(

                "RANKING", "total_revenue", null,

                List.of("region"), List.of(),

                new StructuredSemanticPlan.SemanticAggregations("SUM", null),

                null, null, null, null,

                0.9, "test", List.of());



        var analysis = adapter.toAnalysisPlan(

                "q", "oil_operations", plan,

                SemanticPlanValidationResult.fail("INVALID_METRIC"));



        assertFalse(analysis.executable());

        assertTrue(analysis.blockingReason().contains("INVALID_METRIC"));

    }

}


