package com.example.BACKEND.catalogue.decision.analytics;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Shared utility for extracting and working with numeric row data.
 *
 * All 6 analytical engines share these helpers to avoid duplication.
 * Methods are static and purely computational — no side effects.
 */
public final class RowAnalytics {

    private RowAnalytics() {}

    // ─── type coercion ───────────────────────────────────────────────────

    public static double toDouble(Object v) {
        if (v == null) return Double.NaN;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); }
        catch (Exception e) { return Double.NaN; }
    }

    public static boolean isNumeric(Object v) {
        return !Double.isNaN(toDouble(v));
    }

    // ─── column extraction ───────────────────────────────────────────────

    /**
     * Returns all column keys present in the row set.
     */
    public static Set<String> columns(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return Set.of();
        return rows.get(0).keySet();
    }

    /**
     * Returns only numeric column keys (at least 50% of rows have parseable values).
     */
    public static List<String> numericColumns(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        return columns(rows).stream()
                .filter(col -> {
                    long numeric = rows.stream()
                            .filter(r -> isNumeric(r.get(col))).count();
                    return numeric >= rows.size() * 0.5;
                })
                .collect(Collectors.toList());
    }

    /**
     * Extracts all non-NaN values for a column as a sorted list.
     */
    public static List<Double> values(List<Map<String, Object>> rows, String col) {
        return rows.stream()
                .map(r -> toDouble(r.get(col)))
                .filter(v -> !Double.isNaN(v))
                .sorted()
                .collect(Collectors.toList());
    }

    // ─── descriptive statistics ──────────────────────────────────────────

    public static double mean(List<Double> vals) {
        if (vals.isEmpty()) return 0;
        return vals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    public static double median(List<Double> sorted) {
        if (sorted.isEmpty()) return 0;
        int n = sorted.size();
        return n % 2 == 0
                ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0
                : sorted.get(n / 2);
    }

    public static double stdDev(List<Double> vals) {
        if (vals.size() < 2) return 0;
        double m = mean(vals);
        double variance = vals.stream().mapToDouble(v -> (v - m) * (v - m)).average().orElse(0);
        return Math.sqrt(variance);
    }

    public static double sum(List<Double> vals) {
        return vals.stream().mapToDouble(Double::doubleValue).sum();
    }

    public static double max(List<Double> vals) {
        return vals.stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }

    public static double min(List<Double> vals) {
        return vals.stream().mapToDouble(Double::doubleValue).min().orElse(0);
    }

    /**
     * Pearson-style skewness: (mean - median) / stdDev
     */
    public static double skewness(List<Double> sorted) {
        double m = mean(sorted);
        double med = median(sorted);
        double sd = stdDev(sorted);
        if (sd == 0) return 0;
        return 3.0 * (m - med) / sd;
    }

    /**
     * Share of total held by the top N% of values.
     */
    public static double topNSharePercent(List<Double> sorted, double topFraction) {
        if (sorted.isEmpty() || sum(sorted) == 0) return 0;
        int cutoff = (int) Math.ceil(sorted.size() * (1 - topFraction));
        double topSum = sorted.subList(cutoff, sorted.size()).stream()
                .mapToDouble(Double::doubleValue).sum();
        return 100.0 * topSum / sum(sorted);
    }

    /**
     * Gini-style concentration index (0=equal, 1=fully concentrated).
     */
    public static double concentrationIndex(List<Double> sorted) {
        int n = sorted.size();
        if (n < 2) return 0;
        double total = sum(sorted);
        if (total == 0) return 0;
        double numerator = 0;
        for (int i = 0; i < n; i++) {
            numerator += (2.0 * (i + 1) - n - 1) * sorted.get(i);
        }
        return numerator / (n * total);
    }

    /**
     * Percentile rank of a value within a sorted list (0-100).
     */
    public static double percentileRank(List<Double> sorted, double value) {
        long below = sorted.stream().filter(v -> v < value).count();
        return 100.0 * below / sorted.size();
    }

    // ─── column heuristics ───────────────────────────────────────────────

    /**
     * Identifies columns likely to be "value" metrics (revenue, amount, price).
     */
    public static List<String> valueColumns(List<Map<String, Object>> rows) {
        return numericColumns(rows).stream()
                .filter(k -> {
                    String l = k.toLowerCase();
                    return l.contains("revenue") || l.contains("amount") || l.contains("fare")
                            || l.contains("price") || l.contains("value") || l.contains("total")
                            || l.contains("income") || l.contains("earn") || l.contains("sales");
                })
                .collect(Collectors.toList());
    }

    /**
     * Identifies columns likely to be "volume" or "count" dimensions.
     */
    public static List<String> volumeColumns(List<Map<String, Object>> rows) {
        return numericColumns(rows).stream()
                .filter(k -> {
                    String l = k.toLowerCase();
                    return l.contains("count") || l.contains("trip") || l.contains("volume")
                            || l.contains("num_") || l.contains("_count") || l.contains("qty")
                            || l.contains("quantity") || l.contains("orders");
                })
                .collect(Collectors.toList());
    }

    /**
     * Identifies columns likely to be "dimension" variables (distance, duration, time).
     */
    public static List<String> dimensionColumns(List<Map<String, Object>> rows) {
        return numericColumns(rows).stream()
                .filter(k -> {
                    String l = k.toLowerCase();
                    return l.contains("distance") || l.contains("duration") || l.contains("miles")
                            || l.contains("km") || l.contains("hour") || l.contains("minute")
                            || l.contains("time") || l.contains("length") || l.contains("age");
                })
                .collect(Collectors.toList());
    }
}
