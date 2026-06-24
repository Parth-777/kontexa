package com.example.BACKEND.catalogue.decision.execution.materialization;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Detects pre-aggregated warehouse rows: N rows with one grouping column, one metric column,
 * and optional share_pct. Group keys may be string, numeric, date, or boolean — no type bias.
 */
public final class GroupedWarehouseResultDetector {

    private static final Set<String> METRIC_TOKENS = Set.of(
            "revenue", "profit", "margin", "amount", "value", "total", "sum",
            "avg", "average", "cost", "metric", "measure", "kpi", "score",
            "efficiency", "productivity", "count", "hours", "downtime", "fee"
    );

    private static final Set<String> SHARE_COLUMNS = Set.of(
            "share_pct", "share_percent", "pct", "percent", "percentage",
            "contribution_pct", "revenue_share", "share"
    );

    private static final Set<String> SKIP_COLUMNS = Set.of(
            "share_pct", "share_percent", "row_count", "pct", "percent", "percentage",
            "rank", "contribution_pct", "revenue_share", "share",
            "correlation_coefficient", "corr", "correlation", "pearson_r", "sample_size", "n", "cnt"
    );

    private GroupedWarehouseResultDetector() {}

    public record GroupedShape(
            String dimensionColumn,
            String metricColumn,
            String shareColumn,
            String reason
    ) {}

    public static GroupedShape detect(List<Map<String, Object>> rows) {
        if (rows == null || rows.size() < 2) return null;

        Map<String, Object> sample = rows.getFirst();
        if (sample == null || sample.size() < 2) return null;

        List<String> columns = new ArrayList<>(sample.keySet());
        String shareCol = findShareColumn(columns);
        String metric = pickMetricColumn(columns, shareCol, rows);
        String dimension = pickGroupingColumn(columns, metric, shareCol, rows);

        if (dimension == null || metric == null || dimension.equalsIgnoreCase(metric)) {
            return null;
        }

        if (distinctKeys(dimension, rows) < 2) return null;

        long numericRows = rows.stream()
                .map(r -> RowAnalytics.toDouble(r.get(metric)))
                .filter(v -> !Double.isNaN(v))
                .count();
        if (numericRows < 2) return null;

        return new GroupedShape(dimension, metric, shareCol,
                "grouped_aggregate dim=" + dimension + " metric=" + metric
                        + (shareCol != null ? " share=" + shareCol : ""));
    }

    private static String pickGroupingColumn(
            List<String> columns,
            String metricCol,
            String shareCol,
            List<Map<String, Object>> rows
    ) {
        List<String> candidates = columns.stream()
                .filter(col -> !col.equals(metricCol))
                .filter(col -> shareCol == null || !col.equals(shareCol))
                .filter(col -> !isSkipColumn(col))
                .filter(col -> distinctKeys(col, rows) >= 2)
                .toList();

        if (candidates.isEmpty()) return null;

        return candidates.stream()
                .min(Comparator
                        .comparingInt((String col) -> isNumericColumn(col, rows) ? 1 : 0)
                        .thenComparingInt(col -> metricNameScore(col))
                        .thenComparingDouble(col -> avgNumericMagnitude(col, rows))
                        .thenComparingLong(col -> distinctKeys(col, rows)))
                .orElse(null);
    }

    private static String pickMetricColumn(
            List<String> columns,
            String shareCol,
            List<Map<String, Object>> rows
    ) {
        List<String> candidates = columns.stream()
                .filter(col -> shareCol == null || !col.equals(shareCol))
                .filter(col -> !isSkipColumn(col))
                .filter(col -> isNumericColumn(col, rows))
                .toList();

        if (candidates.isEmpty()) return null;

        return candidates.stream()
                .max(Comparator
                        .comparingInt(GroupedWarehouseResultDetector::metricNameScore)
                        .thenComparingDouble(col -> columnValueSum(col, rows)))
                .orElse(candidates.getFirst());
    }

    private static String findShareColumn(List<String> columns) {
        for (String col : columns) {
            String lower = col.toLowerCase(Locale.ROOT);
            if (SHARE_COLUMNS.contains(lower)) return col;
        }
        for (String col : columns) {
            String lower = col.toLowerCase(Locale.ROOT);
            for (String candidate : SHARE_COLUMNS) {
                if (lower.contains(candidate)) return col;
            }
        }
        return null;
    }

    private static boolean isSkipColumn(String col) {
        String lower = col.toLowerCase(Locale.ROOT);
        return SKIP_COLUMNS.contains(lower);
    }

    private static long distinctKeys(String column, List<Map<String, Object>> rows) {
        return rows.stream()
                .map(r -> r.get(column))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .count();
    }

    private static double columnValueSum(String col, List<Map<String, Object>> rows) {
        return rows.stream()
                .map(r -> RowAnalytics.toDouble(r.get(col)))
                .filter(v -> !Double.isNaN(v))
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    private static double avgNumericMagnitude(String col, List<Map<String, Object>> rows) {
        if (!isNumericColumn(col, rows)) return Double.MAX_VALUE;
        return rows.stream()
                .map(r -> RowAnalytics.toDouble(r.get(col)))
                .filter(v -> !Double.isNaN(v))
                .mapToDouble(Math::abs)
                .average()
                .orElse(Double.MAX_VALUE);
    }

    private static int metricNameScore(String col) {
        String lower = col.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : METRIC_TOKENS) {
            if (lower.contains(token)) score += 10;
        }
        if (lower.startsWith("sum_") || lower.startsWith("avg_")) score += 5;
        return score;
    }

    private static boolean isNumericColumn(String col, List<Map<String, Object>> rows) {
        long numeric = rows.stream()
                .map(r -> r.get(col))
                .filter(Objects::nonNull)
                .map(RowAnalytics::toDouble)
                .filter(v -> !Double.isNaN(v))
                .count();
        return numeric >= Math.max(1, rows.size() / 2);
    }
}
