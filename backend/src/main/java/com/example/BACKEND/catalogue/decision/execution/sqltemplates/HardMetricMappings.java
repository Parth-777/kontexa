package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import java.util.Locale;
import java.util.Set;

/**
 * Hard-coded metric and dimension columns for demo datasets — no semantic guessing.
 */
public final class HardMetricMappings {

    private HardMetricMappings() {}

    public static final Set<String> REVENUE_METRICS = Set.of(
            "total_amount", "fare_amount", "tip_amount"
    );

    public static final String PRIMARY_REVENUE = "total_amount";
    public static final String SECONDARY_REVENUE = "fare_amount";
    public static final String TIP_METRIC = "tip_amount";
    public static final String DISTANCE_DIMENSION = "trip_distance";
    public static final String TIME_DIMENSION = "pickup_datetime";
    public static final String PICKUP_ZONE = "PULocationID";
    public static final String WEEKEND_FLAG = "weekend_flag";

    public static String resolveRevenueMetric(String question) {
        String q = question != null ? question.toLowerCase(Locale.ROOT) : "";
        if (q.contains("tip")) return TIP_METRIC;
        if (q.contains("fare") && !q.contains("total")) return SECONDARY_REVENUE;
        return PRIMARY_REVENUE;
    }

    public static boolean isRevenueMetric(String column) {
        return column != null && REVENUE_METRICS.contains(column.toLowerCase(Locale.ROOT));
    }
}
