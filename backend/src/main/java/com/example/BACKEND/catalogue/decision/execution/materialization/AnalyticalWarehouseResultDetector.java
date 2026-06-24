package com.example.BACKEND.catalogue.decision.execution.materialization;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Detects non-grouped analytical warehouse shapes (correlation, scalar) before GROUP BY materialization.
 */
public final class AnalyticalWarehouseResultDetector {

    private static final Set<String> CORRELATION_COLUMNS = Set.of(
            "correlation_coefficient", "corr", "correlation", "pearson_r", "pearson_corr"
    );

    private static final Set<String> SAMPLE_SIZE_COLUMNS = Set.of(
            "row_count", "count", "n", "sample_size", "num_rows", "cnt"
    );

    private static final Set<String> SHARE_COLUMNS = Set.of(
            "share_pct", "share_percent", "pct", "percent", "percentage",
            "contribution_pct", "revenue_share", "share"
    );

    private static final Set<String> SKIP_SCALAR_COLUMNS = Set.of(
            "id", "row_id", "pk", "key"
    );

    private static final Set<String> METRIC_COLUMN_TOKENS = Set.of(
            "amount", "revenue", "total", "sum", "value", "metric", "profit", "cost", "fee"
    );

    private AnalyticalWarehouseResultDetector() {}

    public record CorrelationShape(
            String coefficientColumn,
            String sampleSizeColumn,
            double coefficient,
            long sampleSize
    ) {}

    public record ScalarShape(
            String metricColumn,
            double value,
            String shareColumn,
            Double sharePct,
            String labelColumn,
            String labelValue
    ) {
        public ScalarShape(String metricColumn, double value) {
            this(metricColumn, value, null, null, null, null);
        }
    }

    public static Optional<CorrelationShape> detectCorrelation(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return Optional.empty();

        Map<String, Object> row = rows.getFirst();
        if (row == null || row.isEmpty()) return Optional.empty();

        String coeffCol = findColumn(row, CORRELATION_COLUMNS);
        if (coeffCol == null) return Optional.empty();

        double coefficient = aggregateCoefficient(rows, coeffCol);
        if (Double.isNaN(coefficient)) return Optional.empty();

        String countCol = findColumn(row, SAMPLE_SIZE_COLUMNS);
        long sampleSize = countCol != null ? aggregateSampleSize(rows, countCol) : rows.size();
        if (sampleSize <= 0) sampleSize = rows.size();

        return Optional.of(new CorrelationShape(coeffCol, countCol, coefficient, sampleSize));
    }

    public static Optional<ScalarShape> detectScalar(List<Map<String, Object>> rows) {
        if (rows == null || rows.size() != 1) return Optional.empty();
        if (detectCorrelation(rows).isPresent()) return Optional.empty();

        Map<String, Object> row = rows.getFirst();
        if (row == null || row.isEmpty()) return Optional.empty();

        String shareCol = findColumn(row, SHARE_COLUMNS);
        Double sharePct = shareCol != null
                ? RowAnalytics.toDouble(row.get(shareCol)) : null;
        if (sharePct != null && Double.isNaN(sharePct)) sharePct = null;

        List<String> valueColumns = new ArrayList<>();
        for (String col : row.keySet()) {
            if (isSampleSizeColumn(col) || isSkipColumn(col)) continue;
            if (shareCol != null && col.equals(shareCol)) continue;
            if (!isNumericValue(row.get(col))) continue;
            valueColumns.add(col);
        }

        if (valueColumns.isEmpty() && sharePct == null) return Optional.empty();

        String labelCol = findLabelColumn(row, valueColumns, shareCol);
        String labelValue = labelCol != null ? Objects.toString(row.get(labelCol), "").trim() : null;
        if (labelValue != null && labelValue.isBlank()) labelValue = null;

        if (!valueColumns.isEmpty()) {
            String metricCol = pickMetricColumn(valueColumns);
            double metricVal = RowAnalytics.toDouble(row.get(metricCol));
            if (!Double.isNaN(metricVal)) {
                return Optional.of(new ScalarShape(
                        metricCol, metricVal, shareCol, sharePct, labelCol, labelValue));
            }
        }

        if (sharePct != null) {
            return Optional.of(new ScalarShape(
                    shareCol, sharePct, shareCol, sharePct, labelCol, labelValue));
        }

        return Optional.empty();
    }

    private static String findLabelColumn(
            Map<String, Object> row, List<String> valueColumns, String shareCol
    ) {
        for (String col : row.keySet()) {
            if (valueColumns.contains(col)) continue;
            if (shareCol != null && col.equals(shareCol)) continue;
            if (isSampleSizeColumn(col) || isSkipColumn(col)) continue;
            Object val = row.get(col);
            if (val == null) continue;
            if (!isNumericValue(val)) return col;
        }
        return null;
    }

    private static String pickMetricColumn(List<String> valueColumns) {
        return valueColumns.stream()
                .max(java.util.Comparator.comparingInt(AnalyticalWarehouseResultDetector::metricColumnScore))
                .orElse(valueColumns.getFirst());
    }

    private static int metricColumnScore(String col) {
        String normalized = normalize(col);
        int score = 0;
        for (String token : METRIC_COLUMN_TOKENS) {
            if (normalized.contains(token)) score += 10;
        }
        if (normalized.startsWith("total_") || normalized.startsWith("sum_")) score += 5;
        return score;
    }

    private static double aggregateCoefficient(List<Map<String, Object>> rows, String col) {
        return rows.stream()
                .map(r -> RowAnalytics.toDouble(r.get(col)))
                .filter(v -> !Double.isNaN(v))
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(Double.NaN);
    }

    private static long aggregateSampleSize(List<Map<String, Object>> rows, String col) {
        return (long) rows.stream()
                .map(r -> RowAnalytics.toDouble(r.get(col)))
                .filter(v -> !Double.isNaN(v))
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    private static String findColumn(Map<String, Object> row, Set<String> candidates) {
        for (String col : row.keySet()) {
            String normalized = normalize(col);
            if (candidates.contains(normalized)) return col;
        }
        for (String col : row.keySet()) {
            String normalized = normalize(col);
            for (String candidate : candidates) {
                if (normalized.contains(candidate)) return col;
            }
        }
        return null;
    }

    private static boolean isSampleSizeColumn(String col) {
        String normalized = normalize(col);
        return SAMPLE_SIZE_COLUMNS.contains(normalized)
                || normalized.endsWith("_count") && !normalized.contains("account");
    }

    private static boolean isSkipColumn(String col) {
        String normalized = normalize(col);
        if (SKIP_SCALAR_COLUMNS.contains(normalized)) return true;
        return normalized.endsWith("_id");
    }

    private static boolean isNumericValue(Object value) {
        if (value == null) return false;
        return !Double.isNaN(RowAnalytics.toDouble(value));
    }

    private static String normalize(String col) {
        return col == null ? "" : col.toLowerCase(Locale.ROOT).trim();
    }
}
