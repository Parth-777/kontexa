package com.example.BACKEND.catalogue.investigation;

/**
 * Phase 2 (Investigation Runtime V1) contract — pure data, no behavior.
 *
 * <p>A {@link DriverContribution} after ranking, carrying its 1-based rank and the
 * cumulative percentage of the headline delta explained up to and including this
 * driver (used to bound how many drivers the narrative reports).
 *
 * <p>This is a contract only. It contains no execution logic and no Phase 1 imports.
 */
public record RankedDriver(
        int rank,
        DriverContribution contribution,
        double cumulativeCoveragePct
) {}
