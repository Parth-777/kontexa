package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import java.util.Locale;

/**
 * Derives SQL output column aliases from resolved metric and dimension names.
 */
public final class SqlColumnAliases {

    private SqlColumnAliases() {}

    public static String metricValueAlias(String metricColumn) {
        if (metricColumn == null || metricColumn.isBlank()) {
            return "metric_value";
        }
        return sanitize(metricColumn);
    }

    public static String dimensionOutputAlias(String dimensionColumn, String bucketAlias) {
        if (bucketAlias != null && !bucketAlias.isBlank()) {
            return sanitize(bucketAlias);
        }
        if (dimensionColumn != null && !dimensionColumn.isBlank()) {
            return sanitize(dimensionColumn);
        }
        return "entity";
    }

    private static String sanitize(String name) {
        String trimmed = name.trim();
        if (trimmed.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            return trimmed;
        }
        return trimmed.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
