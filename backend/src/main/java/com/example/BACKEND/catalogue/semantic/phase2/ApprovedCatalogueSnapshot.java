package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.schema.NestedSchemaFieldGraph;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
            List<String> sampleValues,
            boolean container,
            String parentPath
    ) {
        public CatalogueColumn(
                String columnName,
                String role,
                String dataType,
                String description,
                String defaultAggregation,
                List<String> sampleValues
        ) {
            this(columnName, role, dataType, description, defaultAggregation, sampleValues, false, null);
        }
    }

    public List<String> metricColumns() {
        return columns.stream()
                .filter(NestedSchemaFieldGraph::isBindableLeaf)
                .filter(c -> "metric".equalsIgnoreCase(c.role()))
                .map(CatalogueColumn::columnName)
                .toList();
    }

    public List<String> dimensionColumns() {
        return columns.stream()
                .filter(NestedSchemaFieldGraph::isBindableLeaf)
                .filter(c -> isDimensionRole(c.role()))
                .map(CatalogueColumn::columnName)
                .toList();
    }

    public List<String> bindableLeafColumns() {
        return columns.stream()
                .filter(NestedSchemaFieldGraph::isBindableLeaf)
                .map(CatalogueColumn::columnName)
                .toList();
    }

    public boolean hasColumn(String column) {
        return findColumn(column).isPresent();
    }

    public Optional<CatalogueColumn> findColumn(String column) {
        if (column == null || column.isBlank()) {
            return Optional.empty();
        }
        return columns.stream()
                .filter(c -> c.columnName() != null
                        && c.columnName().equalsIgnoreCase(column.trim()))
                .findFirst();
    }

    public boolean isContainer(String column) {
        return findColumn(column).map(NestedSchemaFieldGraph::isContainer).orElse(false);
    }

    public boolean isBindableLeaf(String column) {
        return findColumn(column).map(NestedSchemaFieldGraph::isBindableLeaf).orElse(false);
    }

    public String canonicalColumnName(String column) {
        return findColumn(column).map(CatalogueColumn::columnName).orElse(null);
    }

    private static boolean isDimensionRole(String role) {
        if (role == null) return false;
        String r = role.toLowerCase(Locale.ROOT);
        return r.contains("dimension") || r.contains("timestamp") || r.contains("identifier");
    }
}
