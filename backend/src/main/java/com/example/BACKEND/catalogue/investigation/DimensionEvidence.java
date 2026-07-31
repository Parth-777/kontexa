package com.example.BACKEND.catalogue.investigation;

import java.util.List;

/**
 * Per-member focal/baseline values for one decomposition dimension.
 */
public record DimensionEvidence(
        String dimensionColumn,
        String dimensionLabel,
        List<MemberValue> members
) {
    public record MemberValue(
            String member,
            double baselineValue,
            double focalValue
    ) {}
}
