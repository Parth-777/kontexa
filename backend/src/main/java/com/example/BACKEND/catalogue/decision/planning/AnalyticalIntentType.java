package com.example.BACKEND.catalogue.decision.planning;

/**
 * Canonical analytical intent types that drive the entire reasoning pipeline.
 *
 * Primary intents (question-aware decomposition):
 *   contribution, trend, comparison, anomaly, ranking, correlation,
 *   distribution, retention, efficiency, composition
 *
 * Legacy intents retained for backward compatibility with playbooks and validation.
 */
public enum AnalyticalIntentType {

    /** How much does X contribute to Y as a share or absolute amount. */
    CONTRIBUTION,

    /** How has a metric evolved over time — direction, rate, acceleration. */
    TREND_ANALYSIS,

    /** How does X compare to Y, Z, or a baseline. */
    COMPARISON,

    /** What is behaving abnormally relative to historical pattern or peer norm. */
    ANOMALY_DETECTION,

    /** Which entities rank highest/lowest on a composite or individual metric. */
    RANKING,

    /** How two metrics co-vary — relationship strength and direction. */
    CORRELATION,

    /** How one variable affects or co-moves with another metric — no GROUP BY dimension. */
    RELATIONSHIP,

    /** How a metric is spread across ranges — shape, skew, and concentration. */
    DISTRIBUTION,

    /** Repeat engagement over time — cohort retention curves. */
    RETENTION,

    /** Value generated per unit of activity — yield and productivity. */
    EFFICIENCY,

    /** Part-to-whole structure — mix and portfolio composition. */
    COMPOSITION,

    /** @deprecated Use {@link #DISTRIBUTION}. Kept for playbook/validation compatibility. */
    @Deprecated
    SEGMENTATION,

    /** What will likely happen given current trajectory. */
    FORECASTING,

    /** Which entities or initiatives deserve priority investment or attention. */
    STRATEGIC_PRIORITIZATION,

    /** Why did something happen — drilling into contributing factors. */
    ROOT_CAUSE_INVESTIGATION,

    /** Fallback for mixed or unclassified intent. */
    GENERAL_ANALYSIS;

    /** Normalise legacy intent aliases to primary pipeline intents. */
    public AnalyticalIntentType canonical() {
        return switch (this) {
            case SEGMENTATION -> DISTRIBUTION;
            default -> this;
        };
    }
}
