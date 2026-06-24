package com.example.BACKEND.catalogue.decision.analytics;

import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult.CompositeScore;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds multi-metric composite scores for entities when multiple dimensions are present.
 *
 * Strategic value is never just "highest revenue." This engine:
 *   1. Discovers all numeric dimensions in the data
 *   2. Normalises each dimension to [0,1] using min-max scaling
 *   3. Applies equal or heuristic weights per dimension
 *   4. Computes a composite score per entity (row)
 *   5. Classifies entities into strategic tiers: LEADER / MID_TIER / UNDERPERFORMER / AT_RISK
 *   6. Derives strengths and weaknesses for each entity
 *
 * Requires at least 3 numeric columns and 5 rows to run.
 */
@Component
public class MultiMetricCompositeAnalyzer {

    private static final int    MIN_DIMENSIONS  = 2;
    private static final int    MIN_ROWS        = 5;
    private static final int    MAX_SCORES      = 10;

    public List<CompositeScore> analyse(List<Map<String, Object>> rows) {
        if (rows == null || rows.size() < MIN_ROWS) return List.of();

        List<String> numCols = RowAnalytics.numericColumns(rows);
        if (numCols.size() < MIN_DIMENSIONS) return List.of();

        // Limit to at most 6 dimensions for interpretability
        List<String> dims = numCols.stream().limit(6).collect(Collectors.toList());

        // Compute per-column min/max for normalisation
        Map<String, double[]> ranges = new LinkedHashMap<>();
        for (String col : dims) {
            List<Double> vals = RowAnalytics.values(rows, col);
            if (vals.isEmpty()) continue;
            ranges.put(col, new double[]{RowAnalytics.min(vals), RowAnalytics.max(vals)});
        }

        if (ranges.size() < MIN_DIMENSIONS) return List.of();

        // Build composite scores per row
        List<CompositeScore> scores = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Map<String, Double> dimScores = new LinkedHashMap<>();
            double total = 0;
            int count = 0;

            for (String col : ranges.keySet()) {
                double val = RowAnalytics.toDouble(row.get(col));
                if (Double.isNaN(val)) continue;
                double[] range = ranges.get(col);
                double span = range[1] - range[0];
                double norm = span > 0 ? (val - range[0]) / span : 0.5;
                dimScores.put(col, norm);
                total += norm;
                count++;
            }

            if (count == 0) continue;
            double composite = total / count;

            String entityKey = entityKey(row, i);
            scores.add(new CompositeScore(
                    entityKey,
                    composite,
                    dimScores,
                    tier(composite),
                    strengths(dimScores),
                    weaknesses(dimScores)
            ));
        }

        // Sort by composite score descending
        return scores.stream()
                .sorted(Comparator.comparingDouble(s -> -s.compositeScore()))
                .limit(MAX_SCORES)
                .collect(Collectors.toList());
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private String entityKey(Map<String, Object> row, int index) {
        // Try to find an entity identifier column
        for (String k : row.keySet()) {
            String kl = k.toLowerCase();
            if (kl.contains("zone") || kl.contains("id") || kl.contains("name")
                    || kl.contains("segment") || kl.contains("category")) {
                Object v = row.get(k);
                if (v != null && !RowAnalytics.isNumeric(v)) return v.toString();
            }
        }
        return "Entity-" + (index + 1);
    }

    private String tier(double score) {
        if (score >= 0.7)  return "LEADER";
        if (score >= 0.5)  return "MID_TIER";
        if (score >= 0.3)  return "UNDERPERFORMER";
        return "AT_RISK";
    }

    private List<String> strengths(Map<String, Double> dimScores) {
        return dimScores.entrySet().stream()
                .filter(e -> e.getValue() >= 0.65)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(2)
                .map(e -> String.format("strong %s (%.0f%%ile)",
                        e.getKey().toLowerCase().replace("_", " "),
                        e.getValue() * 100))
                .collect(Collectors.toList());
    }

    private List<String> weaknesses(Map<String, Double> dimScores) {
        return dimScores.entrySet().stream()
                .filter(e -> e.getValue() < 0.35)
                .sorted(Map.Entry.comparingByValue())
                .limit(2)
                .map(e -> String.format("weak %s (%.0f%%ile)",
                        e.getKey().toLowerCase().replace("_", " "),
                        e.getValue() * 100))
                .collect(Collectors.toList());
    }
}
