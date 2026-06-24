package com.example.BACKEND.catalogue.decision.presentation;

/**
 * Transparent evidence metadata for the primary analytical finding.
 */
public record EvidencePanel(
        String metricUsed,
        String groupingUsed,
        String aggregationMethod,
        int    sampleSize,
        String confidenceBasis
) {
    public static EvidencePanel empty() {
        return new EvidencePanel("—", "—", "—", 0, "Insufficient evidence");
    }
}
