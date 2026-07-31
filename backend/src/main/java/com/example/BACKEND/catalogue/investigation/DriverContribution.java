package com.example.BACKEND.catalogue.investigation;

/**
 * Signed contribution of a dimension member to the headline gap.
 */
public record DriverContribution(
        String dimension,
        String member,
        double baselineValue,
        double focalValue,
        double signedContribution,
        double contributionPctOfGap,
        boolean aligned
) {}
