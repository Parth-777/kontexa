package com.example.BACKEND.catalogue.decision.investigation;

/**
 * The confirmed headline movement of the target metric between baseline and observation
 * windows. Provenance keys reference the warehouse queries that produced each value.
 */
public record MetricChange(
        String metricColumn,
        double baselineValue,
        double observationValue,
        double absoluteDelta,
        double percentDelta,
        String observedDirection,
        boolean material,
        String baselineSpecKey,
        String observationSpecKey
) {
    public static final String INCREASE = "INCREASE";
    public static final String DECREASE = "DECREASE";
    public static final String FLAT = "FLAT";
}
