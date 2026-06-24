package com.example.BACKEND.catalogue.decision.execution.materialization;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Executes a single in-memory GROUP BY aggregation.
 *
 * For a given grouping column and a set of rows (which may include derived columns
 * added by {@link DerivedDimensionMaterializer}), produces a fully ranked
 * {@link MaterializedGrouping} with:
 *   - SUM of the primary value metric per group
 *   - SUM of the volume/count metric per group
 *   - share_pct, efficiency_ratio, rank, percentile, tier, multiplier
 *
 * No business domain is assumed.  The column names come from the
 * {@link MaterializationSpec} and the primary metric is resolved by the caller
 * from the {@link com.example.BACKEND.catalogue.decision.execution.framework.SchemaProfile}.
 */
@Component
public class GroupByExecutor {

    private static final int MAX_GROUPS   = 50;
    static final int MIN_GROUPS_PUBLIC = 2;
    private static final int MIN_GROUPS   = MIN_GROUPS_PUBLIC;
    private static final int MIN_SAMPLE   = 1;   // min rows per group

    public MaterializedGrouping execute(
            List<Map<String, Object>> rows,
            MaterializationSpec       spec,
            String                    primaryValueCol,
            String                    volumeCol       // may be null
    ) {
        if (rows == null || rows.isEmpty()) return emptyGrouping(spec);

        // ── GROUP BY ────────────────────────────────────────────────────
        Map<String, Accumulator> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object rawKey = row.get(spec.groupingKey());
            if (rawKey == null) continue;
            String key = rawKey.toString().trim();
            if (key.isBlank()) continue;

            double value  = RowAnalytics.toDouble(row.get(primaryValueCol));
            if (Double.isNaN(value)) continue;

            double volume = volumeCol != null ? RowAnalytics.toDouble(row.get(volumeCol)) : Double.NaN;
            if (Double.isNaN(volume)) volume = 1.0;

            groups.computeIfAbsent(key, k -> new Accumulator()).add(value, volume);
        }

        if (groups.size() < MIN_GROUPS) return emptyGrouping(spec);

        // ── Totals ───────────────────────────────────────────────────────
        double grandTotal  = groups.values().stream().mapToDouble(a -> a.valueSum).sum();
        if (grandTotal <= 0) return emptyGrouping(spec);

        List<Double> groupValues = groups.values().stream()
                .map(a -> a.valueSum)
                .collect(Collectors.toList());
        double peerAvg = RowAnalytics.mean(groupValues);

        // ── Build ranked entries ─────────────────────────────────────────
        List<Map.Entry<String, Accumulator>> sorted = groups.entrySet().stream()
                .filter(e -> e.getValue().sampleCount >= MIN_SAMPLE)
                .sorted(Comparator.comparingDouble((Map.Entry<String, Accumulator> e) ->
                        e.getValue().valueSum).reversed())
                .limit(MAX_GROUPS)
                .collect(Collectors.toList());

        List<Double> sortedValues = sorted.stream()
                .map(e -> e.getValue().valueSum)
                .collect(Collectors.toList());

        List<MaterializedGroupEntry> entries = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            String     key   = sorted.get(i).getKey();
            Accumulator acc  = sorted.get(i).getValue();
            double      pct  = RowAnalytics.percentileRank(sortedValues, acc.valueSum);
            double      eff  = acc.volumeSum > 0 ? acc.valueSum / acc.volumeSum : 0;
            double      mult = peerAvg > 0 ? acc.valueSum / peerAvg : 1.0;
            double      shr  = 100.0 * acc.valueSum / grandTotal;

            entries.add(new MaterializedGroupEntry(
                    spec.displayLabel(),
                    key,
                    acc.valueSum,
                    acc.volumeSum,
                    shr,
                    eff,
                    i + 1,
                    pct,
                    tier(pct),
                    mult
            ));
        }

        double gini = RowAnalytics.concentrationIndex(
                sortedValues.stream().sorted().collect(Collectors.toList()));

        return new MaterializedGrouping(spec, entries, grandTotal, gini, groups.size());
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private MaterializedGrouping emptyGrouping(MaterializationSpec spec) {
        return new MaterializedGrouping(spec, List.of(), 0, 0, 0);
    }

    private String tier(double pct) {
        if (pct >= 90) return "TOP_DECILE";
        if (pct >= 75) return "TOP_QUARTILE";
        if (pct >= 55) return "ABOVE_AVERAGE";
        if (pct >= 35) return "AVERAGE";
        if (pct >= 15) return "BELOW_AVERAGE";
        return "BOTTOM_QUARTILE";
    }

    private static final class Accumulator {
        double valueSum  = 0;
        double volumeSum = 0;
        long   sampleCount = 0;
        void add(double v, double vol) {
            valueSum   += v;
            volumeSum  += vol;
            sampleCount++;
        }
    }
}
