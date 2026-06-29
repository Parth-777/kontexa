package com.example.BACKEND.catalogue.decision.investigation;

/**
 * The signed contribution of a single dimension member to the headline metric change.
 * For additive (SUM/COUNT) measures the member contributions of a dimension sum exactly
 * to the headline delta.
 */
public record DriverContribution(
        String dimensionColumn,
        String dimensionLabel,
        String member,
        double baselineValue,
        double observationValue,
        double absoluteContribution,
        double contributionPct,
        boolean alignedWithHeadline,
        String specKey
) {}
