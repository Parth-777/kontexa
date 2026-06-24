package com.example.BACKEND.catalogue.decision.reasoning;

/**
 * Statistical signals attached to an analytical finding.
 * Moves narratives beyond descriptive aggregation.
 */
public record StatisticalInterpretation(
        boolean concentrated,
        double  concentrationScore,
        boolean highVariance,
        boolean skewed,
        String  skewDirection,
        boolean hasOutliers,
        int     outlierCount,
        String  trendSlope,
        double  trendSlopeValue,
        String  interpretationSummary
) {
    public static StatisticalInterpretation none() {
        return new StatisticalInterpretation(
                false, 0, false, false, "NEUTRAL", false, 0,
                "FLAT", 0, "");
    }
}
