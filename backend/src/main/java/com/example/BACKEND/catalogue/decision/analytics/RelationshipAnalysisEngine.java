package com.example.BACKEND.catalogue.decision.analytics;

import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult.RelationshipSignal;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyses relationships between pairs of numeric variables.
 *
 * Computes Pearson correlation, detects direction, checks for monotonicity,
 * and characterizes the relationship type (linear, diminishing-returns, etc.).
 *
 * Does NOT claim causality — only correlation and directional patterns.
 * All characterizations are framed as "co-moving", "inversely related", etc.
 *
 * Primary pairs probed:
 *   - dimension columns (distance, duration) × value columns (revenue, fare)
 *   - volume columns (count) × value columns (revenue)
 *   - value × efficiency (to detect scale-vs-quality tradeoffs)
 */
@Component
public class RelationshipAnalysisEngine {

    private static final double STRONG_THRESHOLD    = 0.6;
    private static final double MODERATE_THRESHOLD  = 0.35;
    private static final int    MIN_ROWS             = 10;

    public List<RelationshipSignal> analyse(List<Map<String, Object>> rows) {
        if (rows == null || rows.size() < MIN_ROWS) return List.of();

        List<RelationshipSignal> signals = new ArrayList<>();

        List<String> dimCols    = RowAnalytics.dimensionColumns(rows);
        List<String> valueCols  = RowAnalytics.valueColumns(rows);
        List<String> volCols    = RowAnalytics.volumeColumns(rows);

        // dimension × value relationships
        for (String dim : dimCols) {
            for (String val : valueCols) {
                RelationshipSignal s = compute(rows, dim, val);
                if (s != null) signals.add(s);
            }
        }

        // volume × value relationships (scale vs revenue)
        for (String vol : volCols) {
            for (String val : valueCols) {
                if (vol.equals(val)) continue;
                RelationshipSignal s = compute(rows, vol, val);
                if (s != null) signals.add(s);
            }
        }

        // Return top 3 strongest signals
        return signals.stream()
                .sorted(Comparator.comparingDouble(s -> -Math.abs(s.correlationCoefficient())))
                .limit(3)
                .collect(Collectors.toList());
    }

    // ─── core computation ────────────────────────────────────────────────

    private RelationshipSignal compute(List<Map<String, Object>> rows, String col1, String col2) {
        List<double[]> pairs = rows.stream()
                .map(r -> new double[]{RowAnalytics.toDouble(r.get(col1)),
                                       RowAnalytics.toDouble(r.get(col2))})
                .filter(p -> !Double.isNaN(p[0]) && !Double.isNaN(p[1]))
                .collect(Collectors.toList());

        if (pairs.size() < MIN_ROWS) return null;

        double[] x = pairs.stream().mapToDouble(p -> p[0]).toArray();
        double[] y = pairs.stream().mapToDouble(p -> p[1]).toArray();

        double r = pearson(x, y);
        if (Math.abs(r) < 0.15) return null; // too weak to report

        boolean monotonic  = isMonotonic(pairs);
        String  direction  = direction(r, monotonic);
        String  charLabel  = characterize(r, monotonic, col1, col2);
        double  confidence = Math.min(0.95, 0.5 + Math.abs(r) * 0.5);

        return new RelationshipSignal(col1, col2, r, direction, charLabel, confidence);
    }

    private double pearson(double[] x, double[] y) {
        int n = x.length;
        double mx = Arrays.stream(x).average().orElse(0);
        double my = Arrays.stream(y).average().orElse(0);
        double num = 0, dx2 = 0, dy2 = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            num += dx * dy;
            dx2 += dx * dx;
            dy2 += dy * dy;
        }
        double denom = Math.sqrt(dx2 * dy2);
        return denom == 0 ? 0 : num / denom;
    }

    private boolean isMonotonic(List<double[]> pairs) {
        List<double[]> sorted = pairs.stream()
                .sorted(Comparator.comparingDouble(p -> p[0]))
                .collect(Collectors.toList());
        int increases = 0, decreases = 0;
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i)[1] > sorted.get(i - 1)[1]) increases++;
            else if (sorted.get(i)[1] < sorted.get(i - 1)[1]) decreases++;
        }
        int total = increases + decreases;
        if (total == 0) return false;
        return (double) Math.max(increases, decreases) / total > 0.75;
    }

    private String direction(double r, boolean monotonic) {
        if (Math.abs(r) < MODERATE_THRESHOLD) return "WEAK";
        if (!monotonic && Math.abs(r) >= MODERATE_THRESHOLD) return "NONLINEAR";
        return r > 0 ? "POSITIVE" : "NEGATIVE";
    }

    private String characterize(double r, boolean monotonic, String col1, String col2) {
        String x = col1.toLowerCase();
        String y = col2.toLowerCase();
        double ar = Math.abs(r);

        if (ar >= STRONG_THRESHOLD && r > 0 && monotonic)
            return String.format("%s and %s are strongly co-moving (r=%.2f)", col1, col2, r);
        if (ar >= STRONG_THRESHOLD && r < 0)
            return String.format("%s inversely correlates with %s (r=%.2f) — efficiency decay pattern", col1, col2, r);
        if (ar >= MODERATE_THRESHOLD && !monotonic)
            return String.format("%s vs %s shows non-linear behavior — possible inflection or diminishing returns", col1, col2);
        if (ar >= MODERATE_THRESHOLD && r > 0)
            return String.format("%s and %s are moderately co-moving (r=%.2f)", col1, col2, r);
        if (ar >= MODERATE_THRESHOLD)
            return String.format("%s and %s show a moderate inverse relationship (r=%.2f)", col1, col2, r);

        return String.format("Weak relationship between %s and %s (r=%.2f)", col1, col2, r);
    }
}
