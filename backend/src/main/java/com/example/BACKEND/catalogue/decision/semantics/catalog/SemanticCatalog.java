package com.example.BACKEND.catalogue.decision.semantics.catalog;

import java.util.List;

/**
 * Dynamically built view of available metrics and dimensions from the tenant schema.
 */
public record SemanticCatalog(
        String primaryTableRef,
        List<SemanticCatalogEntry> metrics,
        List<SemanticCatalogEntry> dimensions
) {
    public List<String> candidateMetricKeys() {
        return metrics.stream().map(SemanticCatalogEntry::columnName).toList();
    }

    public List<String> candidateDimensionKeys() {
        return dimensions.stream().map(SemanticCatalogEntry::columnName).toList();
    }

    public boolean hasSchema() {
        return !metrics.isEmpty() || !dimensions.isEmpty();
    }
}
