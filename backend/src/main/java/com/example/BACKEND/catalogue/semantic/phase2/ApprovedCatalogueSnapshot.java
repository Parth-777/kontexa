package com.example.BACKEND.catalogue.semantic.phase2;

import java.util.List;
import java.util.Locale;

/**
 * Approved catalogue slice used for GPT planning and validation.
 */
public record ApprovedCatalogueSnapshot(
        String tableRef,
        String qualifiedTableName,
        List<CatalogueColumn> columns
) {
    public record CatalogueColumn(
            String columnName,
            String role,
            String dataType,
            String description,
            String defaultAggregation,
            List<String> sampleValues
    ) {}

    public List<String> metricColumns() {
        return columns.stream()
                .filter(c -> "metric".equalsIgnoreCase(c.role()))
                .map(CatalogueColumn::columnName)
                .toList();
    }

    public List<String> dimensionColumns() {
        return columns.stream()
                .filter(c -> isDimensionRole(c.role()))
                .map(CatalogueColumn::columnName)
                .toList();
    }

    public boolean hasColumn(String column) {
        if (column == null) return false;
        String lower = column.toLowerCase(Locale.ROOT);
        return columns.stream()
                .anyMatch(c -> c.columnName().equalsIgnoreCase(lower));
    }

    private static boolean isDimensionRole(String role) {
        if (role == null) return false;
        String r = role.toLowerCase(Locale.ROOT);
        return r.contains("dimension") || r.contains("timestamp") || r.contains("identifier");
    }
}
