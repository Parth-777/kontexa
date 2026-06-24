package com.example.BACKEND.catalogue.decision.exploration;

import com.example.BACKEND.catalogue.decision.exploration.SoftSemanticValidator.ValidationMode;

/**
 * Planner confidence tiers — map adjusted confidence to execution strategy.
 */
public enum PlannerConfidenceTier {

    HIGH(0.75),
    MEDIUM(0.55),
    LOW(0.0);

    private final double minConfidence;

    PlannerConfidenceTier(double minConfidence) {
        this.minConfidence = minConfidence;
    }

    public static PlannerConfidenceTier fromScore(
            double adjustedConfidence,
            ValidationMode validationMode,
            String candidateSource
    ) {
        if (adjustedConfidence >= HIGH.minConfidence
                && validationMode == ValidationMode.CONFIDENT) {
            return HIGH;
        }
        if (adjustedConfidence >= MEDIUM.minConfidence
                && validationMode != ValidationMode.EXPLORATORY
                && !isHeuristicSource(candidateSource)) {
            return MEDIUM;
        }
        return LOW;
    }

    public AnalyticalExecutionMode executionMode() {
        return switch (this) {
            case HIGH -> AnalyticalExecutionMode.STRICT_SEMANTIC;
            case MEDIUM -> AnalyticalExecutionMode.HYBRID;
            case LOW -> AnalyticalExecutionMode.EXPLORATORY_HEURISTIC;
        };
    }

    private static boolean isHeuristicSource(String source) {
        if (source == null) return false;
        return source.contains("heuristic")
                || source.contains("fallback")
                || source.contains("default");
    }
}
