package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.semantic.phase2.SemanticCatalogueFactory;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanningProperties;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalSqlRendererTest {

    private final CanonicalQueryModelAdapter adapter = new CanonicalQueryModelAdapter();
    private final CanonicalQueryValidator validator = new CanonicalQueryValidator(new SemanticPlanningProperties());
    private final CanonicalSqlRenderer renderer = new CanonicalSqlRenderer();

    @Test
    void rendersGroupedAvgWithOrderAndLimit() {
        var plan = new StructuredSemanticPlan(
                "COMPARISON", "profit_margin", null,
                List.of("oil_field"), List.of(),
                new StructuredSemanticPlan.SemanticAggregations("AVG", null),
                new StructuredSemanticPlan.SemanticOrdering("profit_margin", "DESC"),
                5, null, null,
                0.9, "test", List.of());

        var bundle = MetricResolutionTestSupport.oilBundle();
        var catalogue = SemanticCatalogueFactory.catalogueFrom(null, bundle);
        CanonicalQueryModel canonical = adapter.adapt(plan);

        assertTrue(validator.validate(canonical, catalogue).valid());
        String sql = renderer.render(canonical, catalogue.qualifiedTableName()).sql();

        assertTrue(sql.toUpperCase().contains("AVG(PROFIT_MARGIN)"));
        assertTrue(sql.toUpperCase().contains("GROUP BY OIL_FIELD"));
        assertTrue(sql.toUpperCase().contains("ORDER BY PROFIT_MARGIN DESC"));
        assertTrue(sql.toUpperCase().contains("LIMIT 5"));

        SqlFidelityReport.Result fidelity = SqlFidelityReport.compare(canonical, sql);
        assertTrue(fidelity.allMatch(), fidelity.mutations().toString());
    }

    @Test
    void rendersCorrelationFromBivariate() {
        var plan = new StructuredSemanticPlan(
                "RELATIONSHIP", "profit_margin", "downtime_hours",
                List.of(), List.of(),
                new StructuredSemanticPlan.SemanticAggregations(null, null),
                null, null, "downtime_hours", null,
                0.9, "corr", List.of());

        var bundle = MetricResolutionTestSupport.oilBundle();
        var catalogue = SemanticCatalogueFactory.catalogueFrom(null, bundle);
        CanonicalQueryModel canonical = adapter.adapt(plan);

        assertTrue(validator.validate(canonical, catalogue).valid());
        String sql = renderer.render(canonical, catalogue.qualifiedTableName()).sql();

        assertTrue(sql.toUpperCase().contains("CORR(PROFIT_MARGIN, DOWNTIME_HOURS)"));
        SqlFidelityReport.Result fidelity = SqlFidelityReport.compare(canonical, sql);
        assertTrue(fidelity.relationshipMatch());
    }

    @Test
    void rendersGroupedCorrelationWithPartition() {
        CanonicalQueryModel canonical = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("profit_margin", "AVG"),
                new CanonicalQueryModel.PartitionSpec("oil_field", null),
                List.of(),
                null,
                new CanonicalQueryModel.BivariateSpec("profit_margin", "downtime_hours", "CORR"),
                null,
                null,
                new CanonicalQueryModel.PlannerMetadata(
                        "RELATIONSHIP", 0.9, "test", List.of("oil_field"),
                        "downtime_hours", null));

        var bundle = MetricResolutionTestSupport.oilBundle();
        var catalogue = SemanticCatalogueFactory.catalogueFrom(null, bundle);

        assertTrue(validator.validate(canonical, catalogue).valid());
        String sql = renderer.render(canonical, catalogue.qualifiedTableName()).sql();

        assertTrue(sql.toUpperCase().contains("GROUP BY OIL_FIELD"));
        assertTrue(sql.toUpperCase().contains("CORR(PROFIT_MARGIN, DOWNTIME_HOURS)"));
        SqlFidelityReport.Result fidelity = SqlFidelityReport.compare(canonical, sql);
        assertTrue(fidelity.allMatch(), fidelity.mutations().toString());
    }

    @Test
    void rendersMonthlyTrendPartition() {
        var plan = new StructuredSemanticPlan(
                "TREND", "total_revenue", null,
                List.of("recorded_date"), List.of(),
                new StructuredSemanticPlan.SemanticAggregations("SUM", null),
                null, null, null, "MONTH",
                0.9, "trend", List.of());

        var bundle = MetricResolutionTestSupport.oilBundle();
        var catalogue = SemanticCatalogueFactory.catalogueFrom(null, bundle);
        CanonicalQueryModel canonical = adapter.adapt(plan);

        assertTrue(validator.validate(canonical, catalogue).valid());
        String sql = renderer.render(canonical, catalogue.qualifiedTableName()).sql();

        assertTrue(sql.toUpperCase().contains("DATE_TRUNC('MONTH', RECORDED_DATE)"));
        assertTrue(sql.toUpperCase().contains("SUM(TOTAL_REVENUE)"));
    }

    @Test
    void rejectsSelfCorrelationOperands() {
        var plan = new StructuredSemanticPlan(
                "RELATIONSHIP", "carbon_emission_tons", "profit_margin",
                List.of(), List.of(),
                new StructuredSemanticPlan.SemanticAggregations(null, null),
                null, null, "carbon_emission_tons", null,
                0.9, "dup", List.of());

        var bundle = MetricResolutionTestSupport.oilBundle();
        var catalogue = SemanticCatalogueFactory.catalogueFrom(null, bundle);
        CanonicalQueryModel canonical = adapter.adapt(plan);

        assertFalse(validator.validate(canonical, catalogue).valid());
    }
}
