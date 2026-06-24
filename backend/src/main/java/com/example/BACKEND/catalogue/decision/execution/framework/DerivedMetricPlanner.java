package com.example.BACKEND.catalogue.decision.execution.framework;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.ConstructedEntity;
import com.example.BACKEND.catalogue.decision.execution.framework.ComputationBlueprint.ComputationStrategy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Enriches constructed entities with derived metrics.
 *
 * Derived metrics are computed generically from the schema profile:
 *
 *   EFFICIENCY RATIO  = VALUE_col / VOLUME_col   (e.g. output per unit)
 *   THROUGHPUT        = VALUE_col / TIME_col      (e.g. output per period)
 *   SHARE_PCT         = entity_value / total_value × 100
 *   CONCENTRATION     = entity share relative to equal-share baseline
 *   DELTA             = current_value - reference_value (if time present)
 *   Z_SCORE           = (value - mean) / stdDev
 *
 * No domain-specific column names are used. All computations are driven by
 * the column roles discovered by {@link SchemaProfiler}.
 *
 * Derived metric keys use generic labels:
 *   "efficiency_ratio", "share_pct", "z_score", "delta", "throughput"
 * Suffixed with the column name for traceability.
 */
@Component
public class DerivedMetricPlanner {

    public List<ConstructedEntity> enrich(
            List<ConstructedEntity> entities,
            SchemaProfile           profile,
            ComputationBlueprint    blueprint
    ) {
        if (entities.isEmpty()) return entities;

        // Pre-compute totals and peer statistics for share and z-score
        Map<String, Double> totals  = computeTotals(entities, profile);
        Map<String, double[]> stats = computeStats(entities, profile); // [mean, stdDev]

        return entities.stream()
                .map(e -> deriveMetrics(e, profile, blueprint, totals, stats))
                .collect(Collectors.toList());
    }

    // ─── per-entity derivation ───────────────────────────────────────────

    private ConstructedEntity deriveMetrics(
            ConstructedEntity    entity,
            SchemaProfile        profile,
            ComputationBlueprint blueprint,
            Map<String, Double>  totals,
            Map<String, double[]> stats
    ) {
        Map<String, Double> m = new LinkedHashMap<>(entity.metrics());

        // 1. EFFICIENCY RATIO: value / volume
        if (blueprint.requiresEfficiency() || blueprint.computationStrategy() == ComputationStrategy.EFFICIENCY_RANKING) {
            ColumnProfile valCol = profile.primaryValue();
            ColumnProfile volCol = profile.primaryVolume();
            if (valCol != null && volCol != null) {
                double val = m.getOrDefault(valCol.columnName(), Double.NaN);
                double vol = m.getOrDefault(volCol.columnName(), Double.NaN);
                if (!Double.isNaN(val) && !Double.isNaN(vol) && vol > 0) {
                    m.put("efficiency_ratio", val / vol);
                }
            }
            // Also derive efficiency for all value×volume pairs (up to 3)
            int ratioCount = 0;
            outer:
            for (ColumnProfile v : profile.valueColumns()) {
                for (ColumnProfile vol : profile.volumeColumns()) {
                    if (ratioCount >= 3) break outer;
                    double vVal = m.getOrDefault(v.columnName(), Double.NaN);
                    double volVal = m.getOrDefault(vol.columnName(), Double.NaN);
                    if (!Double.isNaN(vVal) && !Double.isNaN(volVal) && volVal > 0) {
                        String key = "ratio_" + shortName(v.columnName()) + "_per_" + shortName(vol.columnName());
                        if (!m.containsKey(key)) {
                            m.put(key, vVal / volVal);
                            ratioCount++;
                        }
                    }
                }
            }
        }

        // 2. SHARE_PCT: entity value as % of total
        if (blueprint.requiresConcentration() || blueprint.requiresPeerComparison()) {
            for (ColumnProfile valCol : profile.valueColumns()) {
                double val   = m.getOrDefault(valCol.columnName(), Double.NaN);
                double total = totals.getOrDefault(valCol.columnName(), 0.0);
                if (!Double.isNaN(val) && total > 0) {
                    m.put("share_pct_" + shortName(valCol.columnName()), 100.0 * val / total);
                }
            }
        }

        // 3. Z_SCORE: deviation from peer mean in standard deviations
        boolean wantsZScore = blueprint.requiresOutlierDetection()
                || blueprint.computationStrategy() == ComputationStrategy.ANOMALY_DETECTION;
        if (wantsZScore) {
            for (ColumnProfile valCol : profile.valueColumns()) {
                double val = m.getOrDefault(valCol.columnName(), Double.NaN);
                double[] st = stats.getOrDefault(valCol.columnName(), new double[]{0, 1});
                double mean = st[0], sd = st[1];
                if (!Double.isNaN(val) && sd > 0) {
                    m.put("z_score_" + shortName(valCol.columnName()), (val - mean) / sd);
                }
            }
        }

        // 4. CONCENTRATION check: multiplier vs equal-share baseline
        if (blueprint.requiresConcentration()) {
            ColumnProfile pv = profile.primaryValue();
            if (pv != null) {
                double val   = m.getOrDefault(pv.columnName(), Double.NaN);
                double total = totals.getOrDefault(pv.columnName(), 0.0);
                int    n     = m.getOrDefault("_sample_count", 1.0).intValue();
                // Equal share baseline = total / number of entities
                // We store it as a multiplier
                double equalShare = total > 0 ? total / Math.max(1, n) : 0;
                if (!Double.isNaN(val) && equalShare > 0) {
                    m.put("concentration_multiplier", val / equalShare);
                }
            }
        }

        return new ConstructedEntity(entity.entityKey(), entity.entityType(), m, entity.sampleSize());
    }

    // ─── peer statistics ─────────────────────────────────────────────────

    private Map<String, Double> computeTotals(List<ConstructedEntity> entities, SchemaProfile profile) {
        Map<String, Double> totals = new LinkedHashMap<>();
        for (ColumnProfile col : profile.valueColumns()) {
            double total = entities.stream()
                    .mapToDouble(e -> e.metrics().getOrDefault(col.columnName(), 0.0))
                    .sum();
            totals.put(col.columnName(), total);
        }
        return totals;
    }

    private Map<String, double[]> computeStats(List<ConstructedEntity> entities, SchemaProfile profile) {
        Map<String, double[]> stats = new LinkedHashMap<>();
        for (ColumnProfile col : profile.valueColumns()) {
            List<Double> vals = entities.stream()
                    .map(e -> e.metrics().getOrDefault(col.columnName(), Double.NaN))
                    .filter(v -> !Double.isNaN(v))
                    .collect(Collectors.toList());
            stats.put(col.columnName(), new double[]{
                    RowAnalytics.mean(vals), RowAnalytics.stdDev(vals)});
        }
        return stats;
    }

    private String shortName(String col) {
        return col.toLowerCase().replaceAll("[^a-z0-9]", "_");
    }
}
