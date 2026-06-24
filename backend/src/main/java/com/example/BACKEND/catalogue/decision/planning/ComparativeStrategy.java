package com.example.BACKEND.catalogue.decision.planning;

/**
 * Comparative strategies that define HOW evidence should be compared.
 *
 * Multiple strategies can be active simultaneously for a single investigation plan.
 * The strategy set is selected by {@link ComparativeFrameworkBuilder} based on
 * the analytical intent type.
 */
public enum ComparativeStrategy {

    /** Current vs immediately preceding period. */
    PERIOD_OVER_PERIOD,

    /** Current vs same period one year prior. */
    YEAR_OVER_YEAR,

    /** Current vs computed historical average baseline. */
    VS_HISTORICAL_BASELINE,

    /** Entity performance vs cohort/peer group average. */
    VS_PEER_AVERAGE,

    /** Percentile rank within the distribution (0-100). */
    PERCENTILE_RANK,

    /** Z-score: standard deviations from the mean. Flags statistical anomalies. */
    Z_SCORE_DEVIATION,

    /** Herfindahl-style concentration index — detects dependency risk. */
    CONCENTRATION_RATIO,

    /** Weighted composite score across multiple normalised dimensions. */
    WEIGHTED_COMPOSITE_SCORE,

    /** Direct entity-to-entity delta (absolute and percentage). */
    ENTITY_DELTA
}
