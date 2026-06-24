package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.EnrichedColInfo;
import com.example.BACKEND.catalogue.agent.KpiDetectorService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Plans which columns and executive probes to run — schema-driven, not hardcoded per table.
 */
public final class ColumnDiscoveryPlanner {

    private static final Set<String> REVENUE_KEYWORDS = Set.of(
            "revenue", "fare", "amount", "total", "sales", "price", "income", "payment", "charge");
    private static final Set<String> BEHAVIOR_KEYWORDS = Set.of(
            "tip", "distance", "duration", "time", "speed", "efficiency", "utilization", "hour");
    private static final Set<String> SEGMENT_KEYWORDS = Set.of(
            "zone", "location", "borough", "pickup", "dropoff", "vendor", "type", "flag",
            "rate", "category", "segment", "channel", "payment", "store", "passenger", "airport");

    private ColumnDiscoveryPlanner() {}

    public record RoutePair(String pickupColumn, String dropoffColumn) {}

    public record DiscoveryPlan(
            List<String> revenueMetrics,
            List<String> behaviorMetrics,
            List<String> segmentDimensions,
            Optional<RoutePair> routePair
    ) {}

    public static DiscoveryPlan plan(
            KpiDetectorService.ColumnHints rawHints,
            Map<String, EnrichedColInfo> enriched,
            int maxMetrics,
            int maxDims
    ) {
        List<String> revenue = pickMetrics(rawHints.numericCols(), enriched, REVENUE_KEYWORDS, maxMetrics);
        List<String> behavior = pickMetrics(rawHints.numericCols(), enriched, BEHAVIOR_KEYWORDS, maxMetrics);
        List<String> dims = pickDimensions(rawHints, maxDims);
        Optional<RoutePair> route = findRoutePair(dims, rawHints.stringCols());

        // Ensure at least some metrics for discovery
        if (revenue.isEmpty()) {
            revenue = pickMetrics(rawHints.numericCols(), enriched, Set.of(), Math.min(2, maxMetrics));
        }

        return new DiscoveryPlan(revenue, behavior, dims, route);
    }

    /** All segment columns worth profiling (broader than tier-limited hints). */
    public static List<String> pickDimensions(KpiDetectorService.ColumnHints rawHints, int max) {
        List<Scored> scored = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String col : rawHints.stringCols()) {
            if (isExcludedColumn(col)) continue;
            int score = segmentScore(col);
            if (score > 0) scored.add(new Scored(col, score));
            seen.add(col.toLowerCase());
        }

        // Integer-coded categoricals often land in numericCols (rate_code, payment_type)
        for (String col : rawHints.numericCols()) {
            if (seen.contains(col.toLowerCase()) || isExcludedColumn(col)) continue;
            if (isLikelyCategoricalNumeric(col)) {
                scored.add(new Scored(col, segmentScore(col) + 5));
            }
        }

        scored.sort(Comparator.comparingInt(Scored::score).reversed());

        List<String> out = new ArrayList<>();
        for (Scored s : scored) {
            if (out.size() >= max) break;
            out.add(s.name());
        }

        // Fill with any remaining non-ID columns so we don't fixate on one flag
        if (out.size() < max) {
            for (String col : rawHints.stringCols()) {
                if (out.size() >= max) break;
                if (!isExcludedColumn(col) && !out.contains(col)) out.add(col);
            }
        }
        return out;
    }

    private static List<String> pickMetrics(
            List<String> numericCols,
            Map<String, EnrichedColInfo> enriched,
            Set<String> keywords,
            int max
    ) {
        List<Scored> scored = new ArrayList<>();
        for (String col : numericCols) {
            if (isLikelyCategoricalNumeric(col)) continue;
            String lower = col.toLowerCase();
            int score = 0;
            EnrichedColInfo info = enriched.get(lower);
            if (info != null && List.of("SUM", "AVG", "COUNT").contains(info.aggregationMethod())) {
                score += 40;
            }
            for (String kw : keywords) {
                if (lower.contains(kw)) score += 15;
            }
            if (score > 0 || keywords.isEmpty()) scored.add(new Scored(col, Math.max(score, 1)));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        return scored.stream().limit(max).map(Scored::name).toList();
    }

    private static Optional<RoutePair> findRoutePair(List<String> dims, List<String> allString) {
        String pickup = null;
        String dropoff = null;
        List<String> pool = new ArrayList<>(dims);
        pool.addAll(allString);

        for (String col : pool) {
            String lower = col.toLowerCase();
            if (pickup == null && (lower.contains("pickup") || lower.startsWith("pu")
                    || lower.contains("from") && lower.contains("loc"))) {
                pickup = col;
            }
            if (dropoff == null && (lower.contains("dropoff") || lower.startsWith("do")
                    || lower.contains("to") && lower.contains("loc"))) {
                dropoff = col;
            }
        }
        if (pickup != null && dropoff != null && !pickup.equals(dropoff)) {
            return Optional.of(new RoutePair(pickup, dropoff));
        }
        return Optional.empty();
    }

    private static int segmentScore(String col) {
        String lower = col.toLowerCase();
        int score = 0;
        for (String kw : SEGMENT_KEYWORDS) {
            if (lower.contains(kw)) score += 12;
        }
        if (isLocationColumn(lower)) score += 20;
        return score;
    }

    private static boolean isLikelyCategoricalNumeric(String col) {
        String lower = col.toLowerCase();
        if (isLocationColumn(lower)) return false;
        return lower.contains("type") || lower.contains("code") || lower.contains("flag")
                || lower.contains("rate") || lower.contains("payment") || lower.contains("vendor")
                || lower.endsWith("_id") && !isLocationColumn(lower);
    }

    private static boolean isLocationColumn(String lower) {
        return lower.contains("location") || lower.contains("zone") || lower.contains("borough")
                || lower.contains("pickup") || lower.contains("dropoff")
                || lower.contains("pulocation") || lower.contains("dolocation")
                || lower.contains("geo") || lower.contains("lat") || lower.contains("lon");
    }

    /** Exclude row/surrogate keys — but keep location IDs. */
    public static boolean isExcludedColumn(String col) {
        String lower = col.toLowerCase();
        if (isLocationColumn(lower)) return false;
        if (lower.equals("id") || lower.endsWith("_id") || lower.endsWith("_uuid")) return true;
        if (lower.contains("uuid") || lower.contains("hash") || lower.contains("token")) return true;
        return lower.equals("row_num") || lower.equals("index");
    }

    private record Scored(String name, int score) {}
}
