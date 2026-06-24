package com.example.BACKEND.catalogue.decision.semantics;

import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricMatchCandidate;

import java.util.List;

/**
 * Debug trace for schema-driven metric resolution.
 */
public record MetricResolutionDebug(
        List<String> extractedPhrases,
        List<MetricMatchCandidate> candidates,
        MetricMatchCandidate winner,
        List<MetricMatchCandidate> rejected,
        String selectionReason
) {
    public static MetricResolutionDebug empty(String reason) {
        return new MetricResolutionDebug(List.of(), List.of(), null, List.of(), reason);
    }
}
