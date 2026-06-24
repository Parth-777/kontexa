package com.example.BACKEND.catalogue.decision.execution.materialization;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;
import com.example.BACKEND.catalogue.decision.presentation.MetricBucketingEngine;
import com.example.BACKEND.catalogue.decision.presentation.MetricBucketingEngine.Bucket;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Derives numeric range/quantile bucket columns (e.g. trip_distance_bucket) from raw
 * continuous dimensions so GROUP BY can run even when the warehouse returns row-level data.
 */
@Component
public class NumericDimensionBucketer {

    private final MetricBucketingEngine bucketing;

    public NumericDimensionBucketer(MetricBucketingEngine bucketing) {
        this.bucketing = bucketing;
    }

    /**
     * Adds synthetic bucket columns to rows when the plan requests a *_bucket grouping
     * and the source numeric column exists in the data.
     */
    public List<Map<String, Object>> materializeBucketColumns(
            List<Map<String, Object>> rows,
            List<MaterializationSpec> specs
    ) {
        if (rows == null || rows.isEmpty() || specs == null || specs.isEmpty()) return rows;

        List<String> bucketKeys = specs.stream()
                .map(MaterializationSpec::groupingKey)
                .filter(this::isBucketKey)
                .distinct()
                .toList();
        if (bucketKeys.isEmpty()) return rows;

        Map<String, String> bucketToSource = new LinkedHashMap<>();
        for (String bucketKey : bucketKeys) {
            String source = resolveSourceColumn(bucketKey, rows.getFirst().keySet());
            if (source != null) bucketToSource.put(bucketKey, source);
        }
        if (bucketToSource.isEmpty()) return rows;

        Map<String, List<Bucket>> bucketDefs = new LinkedHashMap<>();
        for (var e : bucketToSource.entrySet()) {
            String sourceCol = e.getValue();
            List<Bucket> buckets = fixedRangeBuckets(sourceCol);
            if (buckets.isEmpty()) {
                List<Double> values = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    double v = RowAnalytics.toDouble(row.get(sourceCol));
                    if (!Double.isNaN(v)) values.add(v);
                }
                buckets = bucketing.bucket(values, MetricBucketingEngine.BucketStrategy.QUANTILE);
            }
            if (buckets.size() >= 2) bucketDefs.put(e.getKey(), buckets);
        }
        if (bucketDefs.isEmpty()) return rows;

        return rows.stream()
                .map(row -> enrichRow(row, bucketToSource, bucketDefs))
                .toList();
    }

    private Map<String, Object> enrichRow(
            Map<String, Object> row,
            Map<String, String> bucketToSource,
            Map<String, List<Bucket>> bucketDefs
    ) {
        Map<String, Object> enriched = new LinkedHashMap<>(row);
        for (var e : bucketToSource.entrySet()) {
            String bucketKey = e.getKey();
            List<Bucket> buckets = bucketDefs.get(bucketKey);
            if (buckets == null) continue;
            double v = RowAnalytics.toDouble(row.get(e.getValue()));
            if (Double.isNaN(v)) continue;
            enriched.put(bucketKey, labelForValue(v, buckets));
        }
        return enriched;
    }

    private String labelForValue(double value, List<Bucket> buckets) {
        for (Bucket b : buckets) {
            boolean last = b.max() == Double.MAX_VALUE || b.max() >= 1e15;
            boolean in = last
                    ? value >= b.min()
                    : value >= b.min() && value < b.max();
            if (in) return b.label();
        }
        return buckets.getLast().label();
    }

    private boolean isBucketKey(String key) {
        return key != null && key.toLowerCase(Locale.ROOT).endsWith("_bucket");
    }

    private String resolveSourceColumn(String bucketKey, Iterable<String> columns) {
        String stem = bucketKey.substring(0, bucketKey.length() - "_bucket".length());
        for (String col : columns) {
            if (col == null) continue;
            String lower = col.toLowerCase(Locale.ROOT);
            if (lower.equals(stem) || lower.equals(bucketKey.toLowerCase(Locale.ROOT))) return col;
            if (stem.contains("distance") && lower.contains("distance")) return col;
            if (stem.contains("fare") && lower.contains("fare")) return col;
            if (stem.contains("tip") && lower.contains("tip")) return col;
        }
        return null;
    }

    /**
     * Standard range buckets for continuous dimensions (miles / currency).
     */
    private List<Bucket> fixedRangeBuckets(String sourceColumn) {
        if (sourceColumn == null) return List.of();
        String lower = sourceColumn.toLowerCase(Locale.ROOT);
        double[] boundaries;
        if (lower.contains("distance") || lower.contains("mile")) {
            boundaries = new double[]{0, 1, 3, 5, 10, 20, Double.MAX_VALUE};
        } else if (lower.contains("fare") || lower.contains("tip") || lower.contains("amount")) {
            boundaries = new double[]{0, 5, 10, 20, 50, 100, Double.MAX_VALUE};
        } else {
            return List.of();
        }
        List<Bucket> buckets = new ArrayList<>();
        for (int i = 0; i < boundaries.length - 1; i++) {
            double min = boundaries[i];
            double max = boundaries[i + 1];
            boolean last = i == boundaries.length - 2;
            String label = last
                    ? formatRangeLabel(min, max, true)
                    : formatRangeLabel(min, max, false);
            buckets.add(new Bucket(label, min, max, 0, 0, 0));
        }
        return buckets;
    }

    private String formatRangeLabel(double min, double max, boolean last) {
        if (last && max == Double.MAX_VALUE) {
            return String.format(Locale.ROOT, "%.0f+", min);
        }
        return String.format(Locale.ROOT, "%.0f–%.0f", min, max);
    }
}
