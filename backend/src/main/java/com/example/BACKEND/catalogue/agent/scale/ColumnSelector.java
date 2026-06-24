package com.example.BACKEND.catalogue.agent.scale;

import com.example.BACKEND.catalogue.agent.EnrichedColInfo;
import com.example.BACKEND.catalogue.agent.KpiDetectorService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ranks metrics and dimensions for analysis / rollup — deterministic, not catalogue order.
 */
public final class ColumnSelector {

    private static final Set<String> METRIC_KEYWORDS = Set.of(
            "revenue", "sales", "amount", "price", "cost", "profit", "income",
            "count", "total", "sum", "qty", "quantity", "units", "orders",
            "users", "sessions", "views", "clicks", "visits", "transactions",
            "rate", "conversion", "churn", "retention", "score", "value",
            "volume", "spend", "budget", "mrr", "arr", "ltv", "arpu", "cac", "nps", "dau", "mau",
            "fare", "tip", "toll", "distance", "duration", "extra", "fee", "charge"
    );

    private static final Set<String> DIMENSION_KEYWORDS = Set.of(
            "region", "country", "city", "state", "zone", "location", "geo",
            "category", "type", "status", "platform", "channel", "medium",
            "segment", "group", "class", "tier", "level", "plan",
            "product", "brand", "source", "campaign", "version", "cohort",
            "device", "os", "browser", "market", "vertical", "industry",
            "pickup", "dropoff", "borough", "vendor", "payment", "passenger", "flag", "rate"
    );

    private static final Set<String> ID_LIKE = Set.of("_id", "id", "uuid", "guid", "hash", "token", "email", "phone");

    private ColumnSelector() {}

    public static List<String> selectMetrics(
            List<String> numericCols,
            Map<String, EnrichedColInfo> enriched,
            int max
    ) {
        List<Scored> scored = new ArrayList<>();
        for (String col : numericCols) {
            EnrichedColInfo info = enriched.get(col.toLowerCase());
            int score = metricScore(col, info);
            if (score > 0) scored.add(new Scored(col, score));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        return scored.stream().limit(max).map(Scored::name).toList();
    }

    public static List<String> selectDimensions(
            List<String> stringCols,
            ScaleTier tier,
            int max
    ) {
        List<Scored> scored = new ArrayList<>();
        for (String col : stringCols) {
            if (isIdLike(col)) continue;
            int score = dimensionScore(col, tier);
            if (score > 0) scored.add(new Scored(col, score));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        if (scored.isEmpty() && tier == ScaleTier.SMALL) {
            return stringCols.stream().filter(c -> !isIdLike(c)).limit(max).toList();
        }
        return scored.stream().limit(max).map(Scored::name).toList();
    }

    private static int metricScore(String col, EnrichedColInfo info) {
        String lower = col.toLowerCase();
        if (isIdLike(lower)) return 0;
        int score = 0;
        if (info != null) {
            String agg = info.aggregationMethod();
            if ("SUM".equals(agg) || "COUNT".equals(agg) || "AVG".equals(agg)) score += 50;
            if ("LAST_VALUE".equals(agg)) score += 20;
        }
        for (String kw : METRIC_KEYWORDS) {
            if (lower.contains(kw)) score += 10;
        }
        return score;
    }

    private static int dimensionScore(String col, ScaleTier tier) {
        String lower = col.toLowerCase();
        int score = 0;
        for (String kw : DIMENSION_KEYWORDS) {
            if (lower.contains(kw)) score += 15;
        }
        if (tier == ScaleTier.SMALL && score == 0) score = 1;
        return score;
    }

    private static boolean isIdLike(String col) {
        return com.example.BACKEND.catalogue.agent.executive.ColumnDiscoveryPlanner.isExcludedColumn(col);
    }

    /**
     * Picks up to {@code limit} dimensions for distribution profiling — prefers scored columns,
     * then fills with any non-ID string columns so large tables still get diverse insights.
     */
    public static List<String> selectDimensionsForDistribution(
            List<String> stringCols,
            ScaleTier tier,
            int limit
    ) {
        List<String> ranked = new ArrayList<>(selectDimensions(stringCols, tier, limit));
        if (ranked.size() >= limit) return ranked.stream().limit(limit).toList();

        Set<String> picked = new HashSet<>(ranked);
        for (String col : stringCols) {
            if (ranked.size() >= limit) break;
            if (isIdLike(col) || picked.contains(col)) continue;
            ranked.add(col);
            picked.add(col);
        }
        return ranked;
    }

    private record Scored(String name, int score) {}
}
