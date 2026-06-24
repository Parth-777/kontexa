package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scalar aggregate SQL for filtered single-metric questions (no GROUP BY).
 * Bypasses template engine — same pattern as Phase-1 experiment.
 */
public final class SemanticPlanScalarSqlBuilder {

    private SemanticPlanScalarSqlBuilder() {}

    public static boolean isScalarFiltered(StructuredSemanticPlan plan) {
        if (plan == null || plan.intent() == null) return false;
        if (!"SCALAR".equalsIgnoreCase(plan.intent())) return false;
        boolean noDim = plan.dimensions() == null || plan.dimensions().isEmpty();
        boolean hasFilter = plan.filters() != null && !plan.filters().isEmpty();
        return noDim && hasFilter && plan.metric() != null && !plan.metric().isBlank();
    }

    public static QuerySpec build(String tableRef, StructuredSemanticPlan plan) {
        String agg = plan.aggregations() != null && plan.aggregations().primary() != null
                ? plan.aggregations().primary().toUpperCase(Locale.ROOT) : "SUM";
        String metric = plan.metric();
        String where = plan.filters().stream()
                .map(SemanticPlanFilterAugmenter::toSqlPredicate)
                .filter(s -> !s.isBlank())
                .reduce((a, b) -> a + " AND " + b)
                .orElse("1=1");
        String sql = "SELECT " + agg + "(" + metric + ") AS " + metric
                + " FROM " + tableRef + " WHERE " + where;
        return new QuerySpec(
                "phase2__scalar__" + metric,
                sql,
                Map.of("metric", metric, "table", tableRef, "intent", "SCALAR"));
    }
}
