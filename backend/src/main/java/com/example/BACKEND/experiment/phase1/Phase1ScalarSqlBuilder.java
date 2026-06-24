package com.example.BACKEND.experiment.phase1;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Experiment-only SQL for scalar aggregates with WHERE filters (no GROUP BY).
 */
public final class Phase1ScalarSqlBuilder {

    private Phase1ScalarSqlBuilder() {}

    public static boolean isScalarFiltered(Phase1PlannerCandidate c) {
        boolean noDim = c.dimensions() == null || c.dimensions().isEmpty();
        boolean hasFilter = c.filters() != null && !c.filters().isEmpty();
        return noDim && hasFilter && c.metric() != null;
    }

    public static QuerySpec build(String tableRef, Phase1PlannerCandidate c) {
        String agg = c.aggregation() != null ? c.aggregation().toUpperCase(Locale.ROOT) : "SUM";
        String metric = c.metric();
        String where = c.filters().stream()
                .map(Phase1FilterSqlAugmenter::toSqlPredicate)
                .filter(s -> !s.isBlank())
                .reduce((a, b) -> a + " AND " + b)
                .orElse("1=1");
        String sql = "SELECT " + agg + "(" + metric + ") AS " + metric
                + " FROM " + tableRef + " WHERE " + where;
        return new QuerySpec(
                "phase1__scalar__" + metric,
                sql,
                Map.of("metric", metric, "table", tableRef, "intent", "SCALAR"));
    }
}
