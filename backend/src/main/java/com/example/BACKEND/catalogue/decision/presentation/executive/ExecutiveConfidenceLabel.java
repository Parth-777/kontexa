package com.example.BACKEND.catalogue.decision.presentation.executive;

/**
 * Qualitative confidence — never expose raw percentages in executive UI.
 */
public enum ExecutiveConfidenceLabel {
    HIGH("High confidence"),
    MODERATE("Moderate confidence"),
    LIMITED("Limited evidence");

    private final String display;

    ExecutiveConfidenceLabel(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    public static ExecutiveConfidenceLabel fromScore(double score, boolean recovery) {
        if (recovery) return LIMITED;
        if (score >= 0.75) return HIGH;
        if (score >= 0.55) return MODERATE;
        return LIMITED;
    }
}
