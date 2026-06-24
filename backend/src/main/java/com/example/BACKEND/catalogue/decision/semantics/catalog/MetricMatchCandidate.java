package com.example.BACKEND.catalogue.decision.semantics.catalog;

/**
 * A scored metric binding candidate from schema-driven matching.
 */
public record MetricMatchCandidate(
        String columnName,
        String registryKey,
        String label,
        double score,
        String matchedPhrase,
        String matchKind,
        boolean accepted
) {
    public static MetricMatchCandidate rejected(
            String columnName, String label, double score, String matchedPhrase, String reason
    ) {
        return new MetricMatchCandidate(
                columnName, null, label, score, matchedPhrase, reason, false);
    }
}
