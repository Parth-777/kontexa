package com.example.BACKEND.catalogue.decision.analytics;

import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult.EfficiencyMetric;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Derives efficiency ratios from raw row data.
 *
 * Executives care about output-per-unit, not just output in isolation.
 * This engine automatically discovers value/volume pairs and computes:
 *   - revenue_per_trip
 *   - revenue_per_mile
 *   - value_per_unit
 *
 * For each entity present in the data, it computes the ratio,
 * positions it against the peer distribution (percentile rank),
 * and assigns a performance tier.
 *
 * This surfaces high-output-vs-high-efficiency tradeoffs — the key
 * analytical gap between "biggest" and "best."
 */
@Component
public class EfficiencyAnalysisEngine {

    private static final int MIN_ROWS = 5;
    private static final int MAX_RATIOS = 6;

    public List<EfficiencyMetric> analyse(List<Map<String, Object>> rows) {
        if (rows == null || rows.size() < MIN_ROWS) return List.of();

        List<String> valueCols  = RowAnalytics.valueColumns(rows);
        List<String> volumeCols = RowAnalytics.volumeColumns(rows);
        List<String> dimCols    = RowAnalytics.dimensionColumns(rows);

        List<EfficiencyMetric> metrics = new ArrayList<>();

        // value ÷ volume ratios (e.g. revenue_per_trip)
        for (String val : valueCols) {
            for (String vol : volumeCols) {
                metrics.addAll(computeRatios(rows, val, vol, ratioLabel(val, vol)));
            }
        }

        // value ÷ dimension ratios (e.g. revenue_per_mile)
        for (String val : valueCols) {
            for (String dim : dimCols) {
                metrics.addAll(computeRatios(rows, val, dim, ratioLabel(val, dim)));
            }
        }

        // De-duplicate by entityKey+ratioLabel, keep strongest
        return metrics.stream()
                .collect(Collectors.toMap(
                        m -> m.entityKey() + "|" + m.ratioLabel(),
                        m -> m,
                        (a, b) -> a.percentile() >= b.percentile() ? a : b
                ))
                .values().stream()
                .sorted(Comparator.comparingDouble(m -> -m.value()))
                .limit(MAX_RATIOS)
                .collect(Collectors.toList());
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private List<EfficiencyMetric> computeRatios(
            List<Map<String, Object>> rows,
            String numeratorCol,
            String denominatorCol,
            String label
    ) {
        // Compute ratio per row, then build peer distribution for percentile ranking
        List<double[]> ratios = rows.stream()
                .map(r -> {
                    double num = RowAnalytics.toDouble(r.get(numeratorCol));
                    double den = RowAnalytics.toDouble(r.get(denominatorCol));
                    if (Double.isNaN(num) || Double.isNaN(den) || den == 0) return null;
                    return new double[]{num / den};
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (ratios.size() < MIN_ROWS) return List.of();

        List<Double> allRatios = ratios.stream()
                .map(r -> r[0]).sorted().collect(Collectors.toList());

        double mean    = RowAnalytics.mean(allRatios);
        double stdDev  = RowAnalytics.stdDev(allRatios);

        // Return aggregate-level efficiency (one metric for the whole result set)
        double pctRank = RowAnalytics.percentileRank(allRatios, mean);

        return List.of(new EfficiencyMetric(
                "aggregate",
                label,
                mean,
                mean,
                pctRank,
                tier(mean, allRatios)
        ));
    }

    private String tier(double value, List<Double> distribution) {
        double pct = RowAnalytics.percentileRank(distribution, value);
        if (pct >= 75) return "TOP";
        if (pct >= 50) return "ABOVE_AVERAGE";
        if (pct >= 25) return "AVERAGE";
        return "BELOW_AVERAGE";
    }

    private String ratioLabel(String numerator, String denominator) {
        String n = numerator.replaceAll("(?i)(total_|avg_|sum_)", "")
                .toLowerCase().replace("_", " ");
        String d = denominator.replaceAll("(?i)(total_|avg_|sum_|num_|count_)", "")
                .toLowerCase().replace("_", " ").replace(" count", "");
        return n + " per " + d.trim();
    }
}
