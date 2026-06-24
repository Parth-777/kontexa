package com.example.BACKEND.catalogue.decision.semantics.catalog;

import java.util.List;

/**
 * A metric or dimension entry in the schema-driven semantic catalog.
 */
public record SemanticCatalogEntry(
        String registryKey,
        String columnName,
        String label,
        String kind,
        String dataType,
        double rankScore,
        List<String> aliases
) {
    public SemanticCatalogEntry(
            String registryKey, String columnName, String label,
            String kind, String dataType, double rankScore
    ) {
        this(registryKey, columnName, label, kind, dataType, rankScore, List.of());
    }
    public boolean isMetric() {
        return "METRIC".equals(kind);
    }

    public boolean isDimension() {
        return "DIMENSION".equals(kind);
    }
}
