package com.example.BACKEND.catalogue.decision.execution;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.ConstructedEntity;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Plans and executes specialized derived metric computations per entity.
 *
 * The planner determines which derived metrics to compute based on:
 *   1. The analytical intent type (what the question is trying to answer)
 *   2. What metric columns are actually available in the entity data
 *
 * Key principle: the right computation depends on the question, not just the data.
 *   "best revenue per minute routes" → must derive revenue/minute per entity
 *   "most efficient zones" → must derive revenue/trip AND revenue/mile
 *   "contribution analysis" → must compute share of total per entity
 *
 * Output: entities enriched with computed derived metrics.
 */
@Component
public class DynamicMetricExecutionPlanner {

    public List<ConstructedEntity> enrichWithDerivedMetrics(
            List<ConstructedEntity> entities,
            AnalyticalIntentType    intentType
    ) {
        if (entities.isEmpty()) return entities;

        return entities.stream()
                .map(e -> enrich(e, intentType, totalAcrossEntities(entities)))
                .collect(Collectors.toList());
    }

    private ConstructedEntity enrich(
            ConstructedEntity entity,
            AnalyticalIntentType intentType,
            Map<String, Double> totals
    ) {
        Map<String, Double> enriched = new LinkedHashMap<>(entity.metrics());
        Map<String, Double> m = entity.metrics();

        // ── Efficiency ratios ──────────────────────────────────────────────
        // Revenue per trip
        double revKey = firstValue(m, "total_fare", "fare_amount", "total_revenue", "revenue", "amount");
        double cntKey = firstValue(m, "trip_count", "count", "trips", "sample_count");
        if (!Double.isNaN(revKey) && !Double.isNaN(cntKey) && cntKey > 0) {
            enriched.put("revenue_per_trip", revKey / cntKey);
        }

        // Revenue per minute / per second
        double durKey = firstValue(m, "trip_duration", "duration", "trip_seconds", "duration_sec",
                "trip_time", "elapsed_seconds");
        if (!Double.isNaN(revKey) && !Double.isNaN(durKey) && durKey > 0) {
            // normalise to minutes
            double durMin = durKey > 1000 ? durKey / 60.0 : durKey; // assume seconds if > 1000
            enriched.put("revenue_per_minute", revKey / Math.max(1, durMin));
        }

        // Revenue per mile
        double distKey = firstValue(m, "trip_distance", "distance", "miles", "trip_miles");
        if (!Double.isNaN(revKey) && !Double.isNaN(distKey) && distKey > 0) {
            enriched.put("revenue_per_mile", revKey / distKey);
        }

        // ── Contribution shares (for CONTRIBUTION intent) ──────────────────
        if (intentType == AnalyticalIntentType.CONTRIBUTION
                || intentType == AnalyticalIntentType.SEGMENTATION) {
            totals.forEach((key, total) -> {
                if (total > 0 && enriched.containsKey(key)) {
                    enriched.put(key + "_share_pct", 100.0 * enriched.get(key) / total);
                }
            });
        }

        // ── Volume efficiency (trips per unit area / time) ─────────────────
        if (!Double.isNaN(cntKey) && !Double.isNaN(durKey) && durKey > 0) {
            enriched.put("trips_per_hour", cntKey / (durKey / 3600.0));
        }

        return new ConstructedEntity(
                entity.entityKey(), entity.entityType(),
                enriched, entity.sampleSize()
        );
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private double firstValue(Map<String, Double> m, String... keys) {
        for (String k : keys) {
            // exact match
            if (m.containsKey(k)) return m.get(k);
            // substring match
            for (Map.Entry<String, Double> e : m.entrySet()) {
                if (e.getKey().contains(k) || k.contains(e.getKey())) return e.getValue();
            }
        }
        return Double.NaN;
    }

    private Map<String, Double> totalAcrossEntities(List<ConstructedEntity> entities) {
        Map<String, Double> totals = new LinkedHashMap<>();
        for (ConstructedEntity e : entities) {
            e.metrics().forEach((k, v) -> {
                if (!Double.isNaN(v)) totals.merge(k, v, Double::sum);
            });
        }
        return totals;
    }
}
