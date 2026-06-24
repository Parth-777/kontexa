package com.example.BACKEND.catalogue.decision.planning;

/**
 * A metric that the investigation plan requires to be computed before synthesis.
 *
 * Metrics are described generically — they are not SQL column names.
 * They represent analytical concepts that the MetricPackPlanner should resolve
 * against the actual schema discovered by the registry.
 *
 * Fields:
 *   metricKey          — semantic key (e.g. "total_value", "efficiency_ratio")
 *   analyticalPurpose  — why this metric is needed for the investigation
 *   aggregationType    — how it should be aggregated (SUM, AVG, COUNT, RATIO, etc.)
 *   normalise          — whether to normalise to [0,1] for scoring/ranking
 *   priority           — 1=critical, 2=important, 3=supplementary
 */
public record MetricRequirement(
        String  metricKey,
        String  analyticalPurpose,
        String  aggregationType,
        boolean normalise,
        int     priority
) {
    public static MetricRequirement critical(String key, String purpose, String agg) {
        return new MetricRequirement(key, purpose, agg, false, 1);
    }

    public static MetricRequirement important(String key, String purpose, String agg) {
        return new MetricRequirement(key, purpose, agg, false, 2);
    }

    public static MetricRequirement scored(String key, String purpose, String agg) {
        return new MetricRequirement(key, purpose, agg, true, 2);
    }
}
