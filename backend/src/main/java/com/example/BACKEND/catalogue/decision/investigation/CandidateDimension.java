package com.example.BACKEND.catalogue.decision.investigation;

/**
 * A catalogue dimension eligible for contribution decomposition.
 */
public record CandidateDimension(
        String column,
        String label,
        String eligibilityReason
) {}
