package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalQueryModelAdapterTest {

    private final CanonicalQueryModelAdapter adapter = new CanonicalQueryModelAdapter();

    @Test
    void pureTranslationPreservesAllPlannerFields() {
        var plan = new StructuredSemanticPlan(
                "RANKING", "total_revenue", "operation_cost",
                List.of("region", "company_name"),
                List.of(new StructuredSemanticPlan.SemanticFilter("region", "=", "EMEA")),
                new StructuredSemanticPlan.SemanticAggregations("SUM", "AVG"),
                new StructuredSemanticPlan.SemanticOrdering("total_revenue", "DESC"),
                5, "profit_margin", "MONTH",
                0.91, "top regions", List.of());

        CanonicalQueryModel cqm = adapter.adapt(plan);

        assertEquals("total_revenue", cqm.measure().column());
        assertEquals("SUM", cqm.measure().aggregation());
        assertEquals("region", cqm.partition().column());
        assertEquals("MONTH", cqm.partition().timeGrain());
        assertEquals(1, cqm.filters().size());
        assertEquals("region", cqm.filters().get(0).column());
        assertEquals("RANKING", cqm.ratio().kind());
        assertEquals("operation_cost", cqm.ratio().denominator().column());
        assertEquals("AVG", cqm.ratio().denominator().aggregation());
        assertEquals("profit_margin", cqm.bivariate().columnB());
        assertNull(cqm.bivariate().function());
        assertEquals("total_revenue", cqm.ordering().column());
        assertEquals("DESC", cqm.ordering().direction());
        assertEquals(5, cqm.limit());
        assertEquals("RANKING", cqm.metadata().intent());
        assertEquals(0.91, cqm.metadata().confidence());
        assertEquals(List.of("region", "company_name"), cqm.metadata().dimensions());
    }

    @Test
    void nullPlanReturnsEmptyModel() {
        CanonicalQueryModel cqm = adapter.adapt(null);
        assertNull(cqm.measure());
        assertNull(cqm.partition());
        assertTrue(cqm.filters().isEmpty());
    }
}
