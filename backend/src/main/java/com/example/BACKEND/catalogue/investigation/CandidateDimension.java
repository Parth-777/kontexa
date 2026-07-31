package com.example.BACKEND.catalogue.investigation;

/**
 * Phase 2 (Investigation Runtime V1) contract — pure data, no behavior.
 *
 * <p>A catalogue dimension judged eligible for change decomposition, together with
 * the reason it was admitted (used for Evidence Pack provenance/auditability).
 *
 * <p>This is a contract only. It contains no execution logic and no Phase 1 imports.
 */
public record CandidateDimension(
        String column,
        String label,
        String eligibilityReason
) {}
