package com.example.BACKEND.catalogue.decision.execution.repair;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Inspects intermediate query output before accepting it for synthesis.
 */
@Component
public class IntermediateResultInspector {

    private static final Set<String> DIMENSION_PRIORITY = Set.of(
            "entity", "dimension", "group", "category", "label", "segment",
            "field", "region", "zone", "bucket", "facility", "type", "name"
    );

    private static final Set<String> METRIC_SKIP = Set.of(
            "share_pct", "row_count", "pct", "percent", "rank", "count"
    );

    public record InspectionResult(
            boolean acceptable,
            String  issue,
            int     distinctGroups,
            double  nullRatio,
            double  variance,
            int     rowCount
    ) {
        public static InspectionResult ok(int rows, int distinct, double variance) {
            return new InspectionResult(true, null, distinct, 0, variance, rows);
        }

        public static InspectionResult fail(String issue, int rows, int distinct, double nullRatio, double variance) {
            return new InspectionResult(false, issue, distinct, nullRatio, variance, rows);
        }
    }

    public InspectionResult inspect(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return InspectionResult.fail("EMPTY", 0, 0, 0, 0);
        }

        String dimKey = detectDimensionKey(rows.getFirst());
        Set<Object> distinct = new HashSet<>();
        int nullCount = 0;
        int totalCells = 0;
        double[] values = new double[rows.size()];
        int valueIdx = 0;

        for (Map<String, Object> row : rows) {
            Object dimVal = dimKey != null ? row.get(dimKey) : row.values().iterator().next();
            if (dimVal == null || "null".equalsIgnoreCase(String.valueOf(dimVal))) {
                nullCount++;
            } else {
                distinct.add(String.valueOf(dimVal));
            }
            totalCells++;
            Double metric = extractMetric(row, dimKey);
            values[valueIdx++] = metric != null ? metric : 0;
        }

        double nullRatio = totalCells > 0 ? (double) nullCount / totalCells : 1;
        if (nullRatio > 0.8) {
            return InspectionResult.fail("NULL_HEAVY", rows.size(), distinct.size(), nullRatio, variance(values));
        }
        if (distinct.size() <= 1) {
            return InspectionResult.fail("SINGLE_VALUE", rows.size(), distinct.size(), nullRatio, variance(values));
        }
        double var = variance(values);
        if (rows.size() >= 2 && var < 1e-9) {
            return InspectionResult.fail("DEGENERATE", rows.size(), distinct.size(), nullRatio, var);
        }
        if (rows.size() < 2) {
            return InspectionResult.fail("FEW_GROUPS", rows.size(), distinct.size(), nullRatio, var);
        }

        return InspectionResult.ok(rows.size(), distinct.size(), var);
    }

    private String detectDimensionKey(Map<String, Object> row) {
        for (String k : row.keySet()) {
            String lower = k.toLowerCase(Locale.ROOT);
            if (DIMENSION_PRIORITY.contains(lower)) return k;
        }
        for (String k : row.keySet()) {
            if (isMetricKey(k, row.get(k))) continue;
            return k;
        }
        return row.keySet().stream().findFirst().orElse(null);
    }

    private boolean isMetricKey(String key, Object value) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (METRIC_SKIP.contains(lower)) return true;
        if (value instanceof Number) return true;
        if (lower.contains("revenue") || lower.contains("amount") || lower.contains("total")
                || lower.contains("avg") || lower.contains("sum") || lower.contains("profit")
                || lower.contains("margin") || lower.contains("cost") || lower.contains("value")) {
            return true;
        }
        return false;
    }

    private Double extractMetric(Map<String, Object> row, String dimKey) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equals(dimKey)) continue;
            if (METRIC_SKIP.contains(e.getKey().toLowerCase(Locale.ROOT))) continue;
            if (e.getValue() instanceof Number n) return n.doubleValue();
        }
        return null;
    }

    private double variance(double[] values) {
        if (values.length < 2) return 0;
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.length;
        double var = 0;
        for (double v : values) var += (v - mean) * (v - mean);
        return var / values.length;
    }
}
