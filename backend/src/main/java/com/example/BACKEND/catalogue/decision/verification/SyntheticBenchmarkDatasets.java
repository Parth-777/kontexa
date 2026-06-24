package com.example.BACKEND.catalogue.decision.verification;

import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedGroupEntry;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializationSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Known synthetic grouped datasets where expected analytical answers are obvious.
 */
public final class SyntheticBenchmarkDatasets {

    private SyntheticBenchmarkDatasets() {}

    public static MaterializedQueryResult revenueByTripDistance() {
        return grouped("trip_distance", "total_amount", List.of(
                entry("1-3", 4500, 45),
                entry("3-5", 2200, 22),
                entry("5-10", 1500, 15),
                entry("0-1", 800, 8),
                entry("10-20", 700, 7),
                entry("20+", 300, 3)
        ));
    }

    public static MaterializedQueryResult revenueByHour() {
        List<MaterializedGroupEntry> entries = new ArrayList<>();
        double[] hours = {0, 6, 12, 17, 18, 19, 20, 23};
        double[] values = {200, 400, 600, 900, 1800, 1600, 1200, 300};
        double total = 0;
        for (double v : values) total += v;
        int rank = 1;
        for (int i = 0; i < hours.length; i++) {
            String label = String.format(Locale.ROOT, "%.0f", hours[i]);
            entries.add(new MaterializedGroupEntry(
                    "hour", label, values[i], 100, 100 * values[i] / total,
                    values[i] / 100, rank++, 0, "TIER", 1));
        }
        entries.sort((a, b) -> Double.compare(b.totalValue(), a.totalValue()));
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            entries.set(i, new MaterializedGroupEntry(
                    e.dimensionName(), e.entityKey(), e.totalValue(), e.volumeCount(),
                    e.sharePct(), e.efficiencyRatio(), i + 1, e.percentileRank(),
                    e.tier(), e.multiplierVsAvg()));
        }
        return wrap("pickup_datetime", "total_amount", entries);
    }

    public static MaterializedQueryResult tipComposition() {
        return grouped("composition", "tip_amount", List.of(
                entry("tips", 1500, 15),
                entry("base_revenue", 8500, 85)
        ));
    }

    public static double tipCompositionTotalRevenue() {
        return 10_000;
    }

    public static MaterializedQueryResult revenueByWeekday() {
        return grouped("day_type", "total_amount", List.of(
                entry("Weekday", 7200, 72),
                entry("Weekend", 2800, 28)
        ));
    }

    public static MaterializedQueryResult topPickupZones() {
        return grouped("PULocationID", "total_amount", List.of(
                entry("132", 1200, 18),
                entry("161", 900, 14),
                entry("237", 700, 11),
                entry("140", 500, 8),
                entry("186", 400, 6)
        ));
    }

    public static MaterializedQueryResult averageFareByDistance() {
        return grouped("trip_distance", "fare_amount", List.of(
                entry("20+", 45, 30),
                entry("10-20", 32, 22),
                entry("5-10", 22, 18),
                entry("3-5", 15, 15),
                entry("1-3", 10, 10),
                entry("0-1", 6, 5)
        ));
    }

    public static MaterializedQueryResult forBenchmark(GoldenAnalyticalBenchmark benchmark) {
        if (benchmark == null) return MaterializedQueryResult.empty();
        return switch (benchmark.id()) {
            case "G01" -> revenueByTripDistance();
            case "G02" -> revenueByHour();
            case "G03" -> tipComposition();
            case "G04" -> revenueByWeekday();
            case "G05" -> topPickupZones();
            case "G06" -> averageFareByDistance();
            default -> MaterializedQueryResult.empty();
        };
    }

    private static MaterializedGroupEntry entry(String bucket, double value, double share) {
        return new MaterializedGroupEntry(
                "bucket", bucket, value, 100, share, value / 100,
                0, 0, "TIER", 1);
    }

    private static MaterializedQueryResult grouped(
            String dimension, String metric, List<MaterializedGroupEntry> raw
    ) {
        List<MaterializedGroupEntry> ranked = new ArrayList<>(raw);
        ranked.sort((a, b) -> Double.compare(b.totalValue(), a.totalValue()));
        double total = ranked.stream().mapToDouble(MaterializedGroupEntry::totalValue).sum();
        List<MaterializedGroupEntry> withRank = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            var e = ranked.get(i);
            double share = total > 0 ? 100.0 * e.totalValue() / total : 0;
            withRank.add(new MaterializedGroupEntry(
                    dimension, e.entityKey(), e.totalValue(), e.volumeCount(),
                    share, e.totalValue() / Math.max(e.volumeCount(), 1),
                    i + 1, 0, "TIER", 1));
        }
        return wrap(dimension, metric, withRank);
    }

    private static MaterializedQueryResult wrap(
            String dimension, String metric, List<MaterializedGroupEntry> entries
    ) {
        double total = entries.stream().mapToDouble(MaterializedGroupEntry::totalValue).sum();
        var spec = new MaterializationSpec(
                dimension, dimension, dimension,
                MaterializationSpec.SpecType.SOURCE_DIMENSION, 0);
        var grouping = new MaterializedGrouping(spec, entries, total, 0.35, entries.size());
        return MaterializedQueryResult.grouped(
                List.of(grouping), grouping, List.of(), metric, entries.size() * 10);
    }

    /** Flat rows for SQL-style verification tests. */
    public static List<Map<String, Object>> flatRevenueByDistance() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var e : revenueByTripDistance().primaryGrouping().rankedEntries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("trip_distance_bucket", e.entityKey());
            row.put("revenue", e.totalValue());
            rows.add(row);
        }
        return rows;
    }
}
