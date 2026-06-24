package com.example.BACKEND.catalogue.decision.presentation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic metric segmentation — range, quantile, and histogram buckets.
 */
@Component
public class MetricBucketingEngine {

    public enum BucketStrategy { RANGE, QUANTILE, HISTOGRAM }

    public record Bucket(String label, double min, double max, long count, double total, double avg) {}

    public List<Bucket> bucket(List<Double> values, BucketStrategy strategy) {
        if (values == null || values.isEmpty()) return List.of();
        List<Double> sorted = values.stream().filter(v -> v != null && !v.isNaN()).sorted().toList();
        if (sorted.isEmpty()) return List.of();

        return switch (strategy) {
            case RANGE -> rangeBuckets(sorted, defaultRangeBoundaries(sorted));
            case QUANTILE -> quantileBuckets(sorted, 4);
            case HISTOGRAM -> histogramBuckets(sorted, 6);
        };
    }

    public List<Bucket> rangeBuckets(List<Double> sortedValues, double[] boundaries) {
        if (sortedValues.isEmpty() || boundaries == null || boundaries.length < 2) return List.of();

        List<Bucket> buckets = new ArrayList<>();
        for (int i = 0; i < boundaries.length - 1; i++) {
            double min = boundaries[i];
            double max = boundaries[i + 1];
            boolean last = i == boundaries.length - 2;

            long count = 0;
            double total = 0;
            for (double v : sortedValues) {
                boolean in = last ? (v >= min && v <= max) : (v >= min && v < max);
                if (in) {
                    count++;
                    total += v;
                }
            }
            if (count > 0) {
                buckets.add(new Bucket(formatRangeLabel(min, max, last), min, max, count, total, total / count));
            }
        }
        return buckets;
    }

    public List<Bucket> quantileBuckets(List<Double> sortedValues, int bucketCount) {
        if (sortedValues.isEmpty() || bucketCount < 2) return List.of();

        List<Bucket> buckets = new ArrayList<>();
        int n = sortedValues.size();
        for (int b = 0; b < bucketCount; b++) {
            int startIdx = (int) Math.floor((double) b * n / bucketCount);
            int endIdx = (int) Math.floor((double) (b + 1) * n / bucketCount) - 1;
            if (endIdx < startIdx) endIdx = startIdx;
            if (startIdx >= n) break;

            double min = sortedValues.get(startIdx);
            double max = sortedValues.get(endIdx);
            long count = endIdx - startIdx + 1L;
            double total = 0;
            for (int i = startIdx; i <= endIdx; i++) total += sortedValues.get(i);

            String label = "Q" + (b + 1) + " (" + formatNumber(min) + "–" + formatNumber(max) + ")";
            buckets.add(new Bucket(label, min, max, count, total, total / count));
        }
        return buckets;
    }

    public List<Bucket> histogramBuckets(List<Double> sortedValues, int binCount) {
        if (sortedValues.isEmpty() || binCount < 2) return List.of();

        double min = sortedValues.getFirst();
        double max = sortedValues.getLast();
        if (min == max) {
            return List.of(new Bucket(formatNumber(min), min, max, sortedValues.size(), min * sortedValues.size(), min));
        }

        double width = (max - min) / binCount;
        double[] boundaries = new double[binCount + 1];
        for (int i = 0; i <= binCount; i++) boundaries[i] = min + i * width;
        boundaries[binCount] = max;
        return rangeBuckets(sortedValues, boundaries);
    }

    /** Default trip-distance style boundaries when data is positive and moderate scale. */
    public double[] defaultRangeBoundaries(List<Double> sortedValues) {
        double max = sortedValues.getLast();
        if (max <= 25) return new double[] { 0, 1, 2, 5, 10, 20, Math.max(20, max) + 0.01 };
        if (max <= 100) return new double[] { 0, 10, 25, 50, 75, 100, max + 0.01 };
        return buildEqualWidthBoundaries(sortedValues.getFirst(), max, 6);
    }

    public double[] buildEqualWidthBoundaries(double min, double max, int bins) {
        double width = (max - min) / bins;
        double[] b = new double[bins + 1];
        for (int i = 0; i <= bins; i++) b[i] = min + i * width;
        b[bins] = max;
        return b;
    }

    public List<Double> extractNumericSeries(List<?> rows, String key) {
        if (rows == null) return List.of();
        List<Double> out = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof java.util.Map<?, ?> m)) continue;
            Object v = m.get(key);
            if (v == null) continue;
            try {
                out.add(Double.parseDouble(v.toString()));
            } catch (NumberFormatException ignored) {}
        }
        Collections.sort(out);
        return out;
    }

    private String formatRangeLabel(double min, double max, boolean last) {
        if (last && min >= 20) return formatNumber(min) + "+";
        return formatNumber(min) + "–" + formatNumber(max);
    }

    private String formatNumber(double n) {
        if (Math.abs(n - Math.rint(n)) < 0.001) return String.format(Locale.ROOT, "%.0f", n);
        return String.format(Locale.ROOT, "%.1f", n);
    }
}
