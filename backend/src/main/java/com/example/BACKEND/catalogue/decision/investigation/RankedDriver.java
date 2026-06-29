package com.example.BACKEND.catalogue.decision.investigation;

/**
 * A driver contribution with its rank and running coverage of the headline delta.
 */
public record RankedDriver(
        int rank,
        DriverContribution contribution,
        double cumulativeCoveragePct
) {}
