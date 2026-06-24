package com.example.BACKEND.catalogue.decision.analytics;

import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult.InflectionPoint;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects inflection points — thresholds where relationship behaviour changes.
 *
 * Example finding:
 *   "Revenue per mile increases until 8 miles, then declines — efficiency optimum at 8 miles."
 *
 * This is executive-grade insight because it identifies actionable thresholds,
 * not just directional trends.
 *
 * Method: sort data by dimension, partition into thirds, compute slope in each
 * partition, detect sign or magnitude reversal between partitions.
 */
@Component
public class InflectionPointDetector {

    private static final int MIN_ROWS       = 15;
    private static final int PARTITIONS     = 3;
    private static final double MIN_REVERSAL = 0.3; // 30% slope change to flag

    public List<InflectionPoint> detect(List<Map<String, Object>> rows) {
        if (rows == null || rows.size() < MIN_ROWS) return List.of();

        List<String> dimCols   = RowAnalytics.dimensionColumns(rows);
        List<String> valueCols = RowAnalytics.valueColumns(rows);

        if (dimCols.isEmpty() || valueCols.isEmpty()) return List.of();

        List<InflectionPoint> points = new ArrayList<>();

        String dimCol   = dimCols.get(0);
        String valueCol = valueCols.get(0);

        // Sort rows by dimension
        List<Map<String, Object>> sorted = rows.stream()
                .filter(r -> RowAnalytics.isNumeric(r.get(dimCol))
                          && RowAnalytics.isNumeric(r.get(valueCol)))
                .sorted(Comparator.comparingDouble(r -> RowAnalytics.toDouble(r.get(dimCol))))
                .collect(Collectors.toList());

        if (sorted.size() < MIN_ROWS) return List.of();

        // Also compute efficiency ratio (value / dim) to detect efficiency inflection
        List<double[]> efficiency = sorted.stream()
                .map(r -> {
                    double d = RowAnalytics.toDouble(r.get(dimCol));
                    double v = RowAnalytics.toDouble(r.get(valueCol));
                    return new double[]{d, d > 0 ? v / d : Double.NaN};
                })
                .filter(p -> !Double.isNaN(p[1]))
                .collect(Collectors.toList());

        // Check for direct value inflection
        InflectionPoint vip = findInflection(sorted, dimCol, valueCol, false);
        if (vip != null) points.add(vip);

        // Check for efficiency inflection (value/dim)
        if (efficiency.size() >= MIN_ROWS) {
            InflectionPoint eip = findEfficiencyInflection(efficiency, dimCol, valueCol);
            if (eip != null) points.add(eip);
        }

        return points;
    }

    // ─── inflection detection ────────────────────────────────────────────

    private InflectionPoint findInflection(
            List<Map<String, Object>> sorted,
            String dimCol, String valueCol,
            boolean efficiency
    ) {
        int n = sorted.size();
        int partSize = n / PARTITIONS;
        if (partSize < 4) return null;

        double[] slopes = new double[PARTITIONS];
        double[] midpoints = new double[PARTITIONS];

        for (int p = 0; p < PARTITIONS; p++) {
            int start = p * partSize;
            int end   = (p == PARTITIONS - 1) ? n : (p + 1) * partSize;
            List<Map<String, Object>> part = sorted.subList(start, end);

            double dimStart = RowAnalytics.toDouble(part.get(0).get(dimCol));
            double dimEnd   = RowAnalytics.toDouble(part.get(part.size() - 1).get(dimCol));
            double valStart = RowAnalytics.mean(RowAnalytics.values(part.subList(0, Math.min(3, part.size())), valueCol));
            double valEnd   = RowAnalytics.mean(RowAnalytics.values(part.subList(Math.max(0, part.size() - 3), part.size()), valueCol));

            slopes[p]    = dimEnd > dimStart ? (valEnd - valStart) / (dimEnd - dimStart) : 0;
            midpoints[p] = (dimStart + dimEnd) / 2.0;
        }

        // Detect sign reversal between partitions 1→2 or 2→3
        for (int i = 0; i < PARTITIONS - 1; i++) {
            double s1 = slopes[i];
            double s2 = slopes[i + 1];
            if (s1 != 0 && Math.abs((s2 - s1) / Math.abs(s1)) >= MIN_REVERSAL) {
                double threshold = midpoints[i + 1];
                boolean risingThenFalling = s1 > 0 && s2 < s1;
                boolean fallingThenStable = s1 < 0 && s2 > s1;

                String below = s1 > 0
                        ? String.format("%s increases with %s (rate: +%.2f per unit)", valueCol, dimCol, s1)
                        : String.format("%s declines with %s (rate: %.2f per unit)", valueCol, dimCol, s1);
                String above = s2 > 0
                        ? String.format("%s continues increasing but at reduced rate (+%.2f)", valueCol, s2)
                        : String.format("%s declines or flattens (rate: %.2f)", valueCol, s2);
                String impl  = risingThenFalling
                        ? String.format("Efficiency optimum near %.1f %s — performance plateaus or declines beyond this threshold", threshold, dimCol)
                        : String.format("Behaviour shift detected at %.1f %s — pattern changes materially beyond this point", threshold, dimCol);

                return new InflectionPoint(dimCol, threshold, below, above, s1, s2, impl);
            }
        }
        return null;
    }

    private InflectionPoint findEfficiencyInflection(List<double[]> pairs, String dimCol, String valueCol) {
        // pairs: [dim, value/dim]
        int n = pairs.size();
        int partSize = n / PARTITIONS;
        if (partSize < 4) return null;

        double[] avgEff = new double[PARTITIONS];
        double[] midDim = new double[PARTITIONS];

        for (int p = 0; p < PARTITIONS; p++) {
            int start = p * partSize;
            int end   = (p == PARTITIONS - 1) ? n : (p + 1) * partSize;
            avgEff[p] = pairs.subList(start, end).stream()
                    .mapToDouble(p2 -> p2[1]).average().orElse(0);
            midDim[p] = pairs.subList(start, end).stream()
                    .mapToDouble(p2 -> p2[0]).average().orElse(0);
        }

        // Find partition where efficiency peaks
        int peakPart = 0;
        for (int p = 1; p < PARTITIONS; p++) {
            if (avgEff[p] > avgEff[peakPart]) peakPart = p;
        }

        boolean hasPeakInMiddle = peakPart > 0 && peakPart < PARTITIONS - 1
                && avgEff[peakPart] > avgEff[peakPart - 1] * 1.1
                && avgEff[peakPart] > avgEff[peakPart + 1] * 1.1;

        if (!hasPeakInMiddle && !(peakPart == 0 && avgEff[0] > avgEff[PARTITIONS - 1] * 1.2))
            return null;

        double threshold = midDim[peakPart];
        String ratioLabel = valueCol.replaceAll("(?i)(total_|avg_|sum_)", "")
                .toLowerCase().replace("_", " ");
        String below = String.format("%s per %s peaks around %.1f %s",
                ratioLabel, dimCol, threshold, dimCol);
        String above = String.format("%s per %s declines beyond %.1f %s — diminishing returns",
                ratioLabel, dimCol, threshold, dimCol);
        String impl  = String.format(
                "Efficiency optimum at ~%.1f %s. Shorter or longer %s shows lower %s efficiency.",
                threshold, dimCol, dimCol, ratioLabel);

        return new InflectionPoint(dimCol, threshold, below, above,
                avgEff[Math.max(0, peakPart - 1)], avgEff[Math.min(PARTITIONS - 1, peakPart + 1)], impl);
    }
}
