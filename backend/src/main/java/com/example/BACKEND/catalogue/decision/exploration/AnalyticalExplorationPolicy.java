package com.example.BACKEND.catalogue.decision.exploration;

/**
 * Exploration policy — prefer plausible analysis over premature rejection.
 */
public final class AnalyticalExplorationPolicy {

    private AnalyticalExplorationPolicy() {}

    public static final double HIGH_CONFIDENCE_THRESHOLD = 0.75;
    public static final double WEAK_CONFIDENCE_THRESHOLD = 0.65;
    public static final double MEDIUM_CONFIDENCE_THRESHOLD = 0.55;
    public static final double MIN_EXPLORATION_CONFIDENCE = 0.35;
    public static final int    MAX_CANDIDATES = 5;
    public static final int    MIN_CANDIDATES = 1;

    public static final String WEAK_INTERPRETATION_NOTE =
            "Using best-match analytical interpretation.";

    public static final String CLOSEST_MATCH_NOTE =
            "Using the strongest matching analytical interpretation.";

    public static final String HYBRID_INTERPRETATION_NOTE =
            "Semantic plan with exploratory aggregation fallback.";

    public static final String EXPLORATION_STEPS = String.join(" → ",
            "identify metric candidates",
            "identify dimension candidates",
            "run top aggregation hypothesis",
            "evaluate result quality");
}
