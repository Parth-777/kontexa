package com.example.BACKEND.catalogue.decision.semantics;

/**
 * Describes how metrics and dimensions relate in the user's question.
 */
public enum AnalyticalRelationship {
    SHARE_OF_TOTAL,
    DIMENSION_BREAKDOWN,
    TREND_OVER_TIME,
    RANKING,
    COMPARISON,
    DISTRIBUTION,
    EFFICIENCY,
    EXACT_LOOKUP,
    /** Two numeric variables — driver/source vs outcome/target, no grouping dimension. */
    METRIC_RELATIONSHIP
}
