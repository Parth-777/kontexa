package com.example.BACKEND.experiment.phase1;

import com.example.BACKEND.catalogue.decision.planning.AnalysisIntent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Rejects LLM column picks that are not present in the supplied catalogue.
 */
public final class Phase1ColumnValidator {

    private Phase1ColumnValidator() {}

    public static List<String> validateColumns(
            Phase1CatalogueSnapshot catalogue,
            List<String> columns,
            Set<String> allowedRoles
    ) {
        List<String> allowed = catalogue.entries().stream()
                .filter(e -> allowedRoles.isEmpty() || allowedRoles.contains(e.role()))
                .map(Phase1CatalogueEntry::columnName)
                .map(c -> c.toLowerCase(Locale.ROOT))
                .toList();
        List<String> out = new ArrayList<>();
        for (String col : columns) {
            if (col != null && allowed.contains(col.toLowerCase(Locale.ROOT))) {
                out.add(col);
            }
        }
        return out;
    }

    public static String validateMetric(Phase1CatalogueSnapshot catalogue, String metric) {
        if (metric == null) return null;
        return catalogue.metricColumns().stream()
                .filter(c -> c.equalsIgnoreCase(metric))
                .findFirst()
                .orElse(null);
    }

    public static List<Phase1FilterSpec> validateFilters(
            Phase1CatalogueSnapshot catalogue,
            List<Phase1FilterSpec> filters
    ) {
        Set<String> dims = Set.copyOf(catalogue.dimensionColumns().stream()
                .map(c -> c.toLowerCase(Locale.ROOT)).toList());
        Set<String> metrics = Set.copyOf(catalogue.metricColumns().stream()
                .map(c -> c.toLowerCase(Locale.ROOT)).toList());
        List<Phase1FilterSpec> out = new ArrayList<>();
        if (filters == null) return out;
        for (Phase1FilterSpec f : filters) {
            if (f == null || f.column() == null) continue;
            String col = f.column().toLowerCase(Locale.ROOT);
            if (dims.contains(col) || metrics.contains(col)) {
                out.add(f);
            }
        }
        return out;
    }

    public static AnalysisIntent parseIntent(String raw) {
        if (raw == null) return AnalysisIntent.CONTRIBUTION;
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "RANKING", "TOP", "BOTTOM" -> AnalysisIntent.RANKING;
            case "TREND", "TIME_SERIES" -> AnalysisIntent.TREND;
            case "COMPARISON", "COMPARE" -> AnalysisIntent.COMPARISON;
            case "DISTRIBUTION", "HISTOGRAM" -> AnalysisIntent.DISTRIBUTION;
            case "SCALAR", "TOTAL", "AGGREGATE" -> AnalysisIntent.CONTRIBUTION;
            default -> AnalysisIntent.CONTRIBUTION;
        };
    }
}
