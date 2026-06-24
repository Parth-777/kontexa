package com.example.BACKEND.catalogue.decision.execution;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.ConstructedEntity;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.RankedEntity;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Produces ranked entity lists from constructed, significance-filtered entities.
 *
 * The runtime produces two distinct rankings:
 *   1. PRIMARY RANKING  — ranked by absolute value (total revenue, total fare, etc.)
 *   2. EFFICIENCY RANKING — ranked by per-unit derived metrics (revenue/trip, revenue/minute)
 *
 * These two rankings frequently disagree — a high-volume entity may rank #1 on
 * absolute value but #15 on efficiency. That gap is analytically valuable.
 *
 * Each ranked entity gets:
 *   - percentile rank within the distribution
 *   - multiplier vs. peer average (e.g. "2.4x the average")
 *   - tier classification (TOP_DECILE, TOP_QUARTILE, ABOVE_AVERAGE, etc.)
 *
 * Tier boundaries:
 *   TOP_DECILE    ≥ 90th percentile
 *   TOP_QUARTILE  ≥ 75th percentile
 *   ABOVE_AVERAGE ≥ 55th percentile
 *   AVERAGE       ≥ 35th percentile
 *   BELOW_AVERAGE ≥ 15th percentile
 *   BOTTOM_QUARTILE < 15th percentile
 */
@Component
public class ComparativeRankingRuntime {

    private static final int MAX_RANKED = 20;

    public List<RankedEntity> rankByPrimary(List<ConstructedEntity> entities) {
        String key = findBestPrimaryKey(entities);
        if (key == null) return List.of();
        return rank(entities, key);
    }

    public List<RankedEntity> rankByEfficiency(List<ConstructedEntity> entities) {
        // Prefer revenue_per_minute, then revenue_per_trip, then revenue_per_mile
        String key = findEfficiencyKey(entities);
        if (key == null) return List.of();
        return rank(entities, key);
    }

    // ─── core ranking ────────────────────────────────────────────────────

    private List<RankedEntity> rank(List<ConstructedEntity> entities, String metricKey) {
        List<ConstructedEntity> withMetric = entities.stream()
                .filter(e -> {
                    Double v = e.metrics().get(metricKey);
                    return v != null && !Double.isNaN(v) && v >= 0;
                })
                .sorted(Comparator.comparingDouble(e -> -e.metrics().get(metricKey)))
                .limit(MAX_RANKED)
                .collect(Collectors.toList());

        if (withMetric.isEmpty()) return List.of();

        List<Double> allVals = withMetric.stream()
                .map(e -> e.metrics().get(metricKey))
                .collect(Collectors.toList());

        double avg = RowAnalytics.mean(allVals);

        List<RankedEntity> ranked = new ArrayList<>();
        for (int i = 0; i < withMetric.size(); i++) {
            ConstructedEntity e = withMetric.get(i);
            double val         = e.metrics().get(metricKey);
            double pctRank     = RowAnalytics.percentileRank(allVals, val);
            double multiplier  = avg > 0 ? val / avg : 1.0;

            ranked.add(new RankedEntity(
                    i + 1,
                    e.entityKey(),
                    metricKey,
                    val,
                    avg,
                    multiplier,
                    pctRank,
                    tier(pctRank)
            ));
        }
        return ranked;
    }

    // ─── key discovery ───────────────────────────────────────────────────

    private String findBestPrimaryKey(List<ConstructedEntity> entities) {
        if (entities.isEmpty()) return null;
        Set<String> keys = entities.get(0).metrics().keySet();
        String[] priority = {
            "total_fare", "fare_amount", "total_revenue", "revenue", "amount",
            "total_value", "income", "earnings"
        };
        for (String p : priority) {
            for (String k : keys) {
                if (k.contains(p)) return k;
            }
        }
        // Fallback: first numeric key that isn't a count or sample
        return keys.stream()
                .filter(k -> !k.contains("count") && !k.contains("sample"))
                .findFirst().orElse(null);
    }

    private String findEfficiencyKey(List<ConstructedEntity> entities) {
        if (entities.isEmpty()) return null;
        Set<String> keys = entities.get(0).metrics().keySet();
        String[] priority = {
            "revenue_per_minute", "revenue_per_trip", "revenue_per_mile",
            "fare_per_trip", "value_per_unit", "trips_per_hour"
        };
        for (String p : priority) {
            for (String k : keys) {
                if (k.equals(p) || k.contains(p)) return k;
            }
        }
        return null;
    }

    // ─── tier classification ─────────────────────────────────────────────

    private String tier(double percentile) {
        if (percentile >= 90) return "TOP_DECILE";
        if (percentile >= 75) return "TOP_QUARTILE";
        if (percentile >= 55) return "ABOVE_AVERAGE";
        if (percentile >= 35) return "AVERAGE";
        if (percentile >= 15) return "BELOW_AVERAGE";
        return "BOTTOM_QUARTILE";
    }
}
