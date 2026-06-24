package com.example.BACKEND.catalogue.decision.candidate;

import com.example.BACKEND.catalogue.decision.exploration.InterpretationCandidatePlan;

/**
 * Executable analytical hypothesis with stable identity for scoring and selection.
 */
public record AnalyticalCandidate(
        String                      candidateId,
        InterpretationCandidatePlan plan,
        String                      dimensionColumn,
        String                      bucketColumn
) {
    public String label() {
        return plan.label();
    }
}
