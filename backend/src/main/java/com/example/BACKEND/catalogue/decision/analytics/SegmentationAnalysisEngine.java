package com.example.BACKEND.catalogue.decision.analytics;

import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult.SegmentBucket;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Automatically bucketizes numeric dimensions and computes per-bucket value aggregates.
 *
 * Core question: "Does behaviour change across segments?"
 *
 * Rather than reporting average trip distance vs average revenue, this engine
 * partitions the data into N quantile buckets and computes aggregate value
 * per bucket — revealing whether behaviour is uniform or varies structurally.
 *
 * Example: trip distance buckets (0–3 mi, 3–7 mi, 7–15 mi, 15+ mi)
 * with per-bucket avg revenue reveals revenue curves and efficiency zones.
 */
@Component
public class SegmentationAnalysisEngine {

    private static final int BUCKET_COUNT = 4;
    private static final int MIN_ROWS_PER_BUCKET = 3;

    public List<SegmentBucket> analyse(List<Map<String, Object>> rows) {
        if (rows == null || rows.size() < BUCKET_COUNT * MIN_ROWS_PER_BUCKET)
            return List.of();

        List<String> dimCols   = RowAnalytics.dimensionColumns(rows);
        List<String> valueCols = RowAnalytics.valueColumns(rows);

        if (dimCols.isEmpty() || valueCols.isEmpty()) return List.of();

        List<SegmentBucket> buckets = new ArrayList<>();

        // Analyse the most prominent dimension × value pair
        String dimCol   = dimCols.get(0);
        String valueCol = valueCols.get(0);

        List<Double> dimVals = RowAnalytics.values(rows, dimCol);
        if (dimVals.isEmpty()) return List.of();

        double[] quantiles = quantileBoundaries(dimVals);
        double totalValue  = rows.stream()
                .mapToDouble(r -> safeDouble(r.get(valueCol))).sum();

        for (int i = 0; i < BUCKET_COUNT; i++) {
            final int    bucketIdx = i;
            double lo = quantiles[i];
            double hi = quantiles[i + 1];

            List<Map<String, Object>> slice = rows.stream()
                    .filter(r -> {
                        double d = safeDouble(r.get(dimCol));
                        return bucketIdx == BUCKET_COUNT - 1
                                ? d >= lo && d <= hi
                                : d >= lo && d < hi;
                    })
                    .collect(Collectors.toList());

            if (slice.size() < MIN_ROWS_PER_BUCKET) continue;

            List<Double> vals = slice.stream()
                    .map(r -> safeDouble(r.get(valueCol)))
                    .filter(v -> !Double.isNaN(v))
                    .collect(Collectors.toList());

            double avg   = RowAnalytics.mean(vals);
            double total = RowAnalytics.sum(vals);
            double share = totalValue > 0 ? 100.0 * total / totalValue : 0;

            buckets.add(new SegmentBucket(
                    dimCol,
                    bucketLabel(dimCol, lo, hi, bucketIdx),
                    lo, hi, avg, total, slice.size(), share,
                    characterize(avg, RowAnalytics.mean(RowAnalytics.values(rows, valueCol)))
            ));
        }

        return buckets;
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private double[] quantileBoundaries(List<Double> sorted) {
        double[] q = new double[BUCKET_COUNT + 1];
        q[0] = sorted.get(0);
        q[BUCKET_COUNT] = sorted.get(sorted.size() - 1);
        for (int i = 1; i < BUCKET_COUNT; i++) {
            int idx = (int) Math.floor(sorted.size() * i / (double) BUCKET_COUNT);
            q[i] = sorted.get(Math.min(idx, sorted.size() - 1));
        }
        return q;
    }

    private String bucketLabel(String dim, double lo, double hi, int bucket) {
        String unit = dim.toLowerCase().contains("distance") || dim.toLowerCase().contains("mile") ? "mi"
                    : dim.toLowerCase().contains("duration") || dim.toLowerCase().contains("minute") ? "min"
                    : dim.toLowerCase().contains("hour") ? "h" : "";
        String tier = switch (bucket) {
            case 0 -> "SHORT";
            case 1 -> "MEDIUM";
            case 2 -> "LONG";
            default -> "EXTENDED";
        };
        return String.format("%s (%.1f–%.1f%s)", tier, lo, hi, unit);
    }

    private String characterize(double bucketAvg, double overallAvg) {
        if (overallAvg == 0) return "UNKNOWN";
        double ratio = bucketAvg / overallAvg;
        if (ratio >= 1.5)  return "HIGH_EFFICIENCY";
        if (ratio >= 1.1)  return "ABOVE_AVERAGE";
        if (ratio >= 0.9)  return "AVERAGE";
        if (ratio >= 0.6)  return "BELOW_AVERAGE";
        return "LOW_EFFICIENCY";
    }

    private double safeDouble(Object v) {
        double d = RowAnalytics.toDouble(v);
        return Double.isNaN(d) ? 0 : d;
    }
}
