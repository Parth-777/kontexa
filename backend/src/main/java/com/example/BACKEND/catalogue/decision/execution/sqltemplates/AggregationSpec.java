package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

/**
 * Resolved aggregation strategy for a grouped SQL query.
 */
public record AggregationSpec(
        MetricAggregation aggregation,
        boolean includeSharePercent,
        boolean includeRowCount,
        String valueAlias
) {
    public static AggregationSpec sum(String alias) {
        return new AggregationSpec(MetricAggregation.SUM, false, false, alias);
    }

    public static AggregationSpec sumWithShare(String alias) {
        return new AggregationSpec(MetricAggregation.SUM, true, false, alias);
    }

    public static AggregationSpec countDistribution() {
        return new AggregationSpec(MetricAggregation.COUNT, true, true, "row_count");
    }

    public static AggregationSpec avg(String alias) {
        return new AggregationSpec(MetricAggregation.AVG, false, false, alias);
    }

    public static AggregationSpec ratio(String alias) {
        return new AggregationSpec(MetricAggregation.RATIO, false, false, alias);
    }
}
