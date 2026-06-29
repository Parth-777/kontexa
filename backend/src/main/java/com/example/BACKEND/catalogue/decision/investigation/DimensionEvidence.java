package com.example.BACKEND.catalogue.decision.investigation;

import java.util.List;

/**
 * Per-dimension baseline vs observation member values, produced by two grouped warehouse
 * queries whose keys are retained for provenance.
 */
public record DimensionEvidence(
        String dimensionColumn,
        String dimensionLabel,
        List<MemberValue> members,
        int memberCount,
        String baselineSpecKey,
        String observationSpecKey
) {
    /**
     * A single dimension member with its measure value in each window.
     */
    public record MemberValue(
            String member,
            double baselineValue,
            double observationValue
    ) {}
}
