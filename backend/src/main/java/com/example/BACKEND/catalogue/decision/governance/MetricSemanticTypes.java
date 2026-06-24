package com.example.BACKEND.catalogue.decision.governance;

/**
 * Canonical metric semantic types for governance.
 */
public final class MetricSemanticTypes {

    private MetricSemanticTypes() {}

    public enum AggregationType {
        SUM, AVG, COUNT, RATIO, RATE, DISTINCT_COUNT
    }

    public enum AdditiveScope {
        FULLY_ADDITIVE,
        SEMI_ADDITIVE,
        NON_ADDITIVE
    }

    public enum BusinessMeaning {
        REVENUE, TRIPS, EFFICIENCY, UTILIZATION, DURATION, DISTANCE,
        COUNT, RATE, SHARE, COMPOSITE, UNKNOWN
    }

    public enum MetricUnit {
        CURRENCY, PERCENT, COUNT, DURATION, RATIO, DISTANCE, UNKNOWN
    }

    public enum ContributionScope {
        GLOBAL,
        LOCAL
    }
}
