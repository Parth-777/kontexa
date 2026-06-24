package com.example.BACKEND.catalogue.decision.exploration;

import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.semantic.QueryEntityResolver;
import com.example.BACKEND.catalogue.decision.semantic.ResolvedEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Heuristic fallback plans when strict semantic parsing is weak.
 */
@Component
public class FallbackAnalyticalHeuristics {

    private final SemanticFallbackDictionary fallbackDict;
    private final QueryEntityResolver entityResolver;

    public FallbackAnalyticalHeuristics(
            SemanticFallbackDictionary fallbackDict,
            QueryEntityResolver entityResolver
    ) {
        this.fallbackDict = fallbackDict;
        this.entityResolver = entityResolver;
    }

    public List<InterpretationCandidatePlan> generate(String question) {
        List<InterpretationCandidatePlan> candidates = new ArrayList<>();
        String q = question != null ? question.toLowerCase(Locale.ROOT) : "";

        if (q.contains("weekend") && (q.contains("revenue") || q.contains("ride") || q.contains("trip"))) {
            candidates.add(plan("Weekend revenue share",
                    "Share of total revenue from weekend rides",
                    "total_amount", "Total Revenue", "weekend_flag", "weekend_flag",
                    AggregationType.SUM, AnalyticalIntentType.COMPOSITION, 0.82, "weekend_heuristic"));
            candidates.add(plan("Weekend vs weekday",
                    "Compare weekend revenue against weekday revenue",
                    "total_amount", "Total Revenue", "weekday", "weekday",
                    AggregationType.SUM, AnalyticalIntentType.COMPARISON, 0.78, "weekend_heuristic"));
            candidates.add(plan("Avg revenue per weekend ride",
                    "Average revenue per ride on weekends",
                    "total_amount", "Total Revenue", "volume", "weekend_flag",
                    AggregationType.RATIO, AnalyticalIntentType.EFFICIENCY, 0.72, "weekend_heuristic"));
            candidates.add(plan("Revenue by day type",
                    "Revenue grouped by weekend vs weekday",
                    "total_amount", "Total Revenue", "weekend_flag", "weekend_flag",
                    AggregationType.SUM, AnalyticalIntentType.CONTRIBUTION, 0.8, "weekend_heuristic"));
        }

        ResolvedEntity metric = entityResolver.firstMetric(question);
        ResolvedEntity dim = entityResolver.firstDimension(question);
        String metricKey = metric != null ? metric.columnKey() : "total_amount";
        String metricLabel = metric != null ? metric.label() : "Total Revenue";

        if (dim != null && isRevenueLike(metricKey, q)) {
            String grouping = bucket(dim.columnKey());
            double conf = hasImpactLanguage(q) ? 0.78 : 0.75;
            candidates.add(plan(metricLabel + " by " + dim.label(),
                    "SUM(" + metricKey + ") grouped by " + dim.label(),
                    metricKey, metricLabel, dim.columnKey(), grouping,
                    AggregationType.SUM, AnalyticalIntentType.CONTRIBUTION, conf, "dimension_revenue_heuristic"));
        }

        if (hasImpactLanguage(q) && dim == null) {
            addDefaultDimensionHeuristics(candidates, metricKey, metricLabel, q);
        }

        if ((q.contains("tip") || metricKey.contains("tip")) && q.contains("revenue")) {
            candidates.add(plan("Tip share of revenue",
                    "SUM(tip_amount) / SUM(total_amount)",
                    "tip_amount", "Tips", "total_amount", null,
                    AggregationType.RATIO, AnalyticalIntentType.COMPOSITION, 0.85, "tip_share_heuristic"));
        }

        for (SemanticFallbackDictionary.FallbackMapping m : fallbackDict.matchAll(question)) {
            if (m.dimensionColumn() != null && m.dimensionColumn().contains("distance")
                    && !q.contains("distance") && !q.contains("mile")) {
                continue;
            }
            if (m.groupingColumn() != null) {
                candidates.add(plan(m.label() + " revenue breakdown",
                        "Revenue grouped by " + m.label(),
                        m.metricColumn(), "Total Revenue", m.dimensionColumn(), m.groupingColumn(),
                        AggregationType.SUM, AnalyticalIntentType.CONTRIBUTION, 0.7, "fallback_dictionary"));
            } else if ("tip_amount".equals(m.metricColumn())) {
                candidates.add(plan("Tip share analysis",
                        "Tip contribution to total revenue",
                        "tip_amount", "Tips", "total_amount", null,
                        AggregationType.RATIO, AnalyticalIntentType.COMPOSITION, 0.8, "fallback_dictionary"));
            }
        }

        if (candidates.isEmpty() && (q.contains("revenue") || q.contains("fare") || hasImpactLanguage(q))) {
            addDefaultDimensionHeuristics(candidates, metricKey, metricLabel, q);
        }

        return candidates;
    }

    private void addDefaultDimensionHeuristics(
            List<InterpretationCandidatePlan> candidates,
            String metricKey,
            String metricLabel,
            String q
    ) {
        if (q.contains("distance") || q.contains("mile")) {
            candidates.add(plan("Revenue by trip distance",
                    "GROUP BY trip_distance_bucket SUM(revenue)",
                    metricKey, metricLabel, "trip_distance", "trip_distance_bucket",
                    AggregationType.SUM, AnalyticalIntentType.CONTRIBUTION, 0.72, "distance_heuristic"));
        }
        if (q.contains("zone") || q.contains("pickup") || q.contains("location")) {
            candidates.add(plan("Revenue by pickup zone",
                    "GROUP BY pickup_zone SUM(revenue)",
                    metricKey, metricLabel, "pickup_zone", "pickup_zone",
                    AggregationType.SUM, AnalyticalIntentType.CONTRIBUTION, 0.7, "zone_heuristic"));
        }
        if (q.contains("vendor") || q.contains("provider")) {
            candidates.add(plan("Revenue by vendor",
                    "GROUP BY vendor SUM(revenue)",
                    metricKey, metricLabel, "vendor_id", "vendor_id",
                    AggregationType.SUM, AnalyticalIntentType.CONTRIBUTION, 0.7, "vendor_heuristic"));
        }
    }

    private boolean hasImpactLanguage(String q) {
        return q.contains("contribute") || q.contains("affect") || q.contains("impact")
                || q.contains("drive") || q.contains("influence") || q.contains("relate");
    }

    private boolean isRevenueLike(String metricKey, String q) {
        return metricKey.contains("amount") || metricKey.contains("revenue")
                || q.contains("revenue") || q.contains("fare");
    }

    private String bucket(String dim) {
        if (dim == null) return null;
        if (dim.endsWith("_bucket")) return dim;
        if ("trip_distance".equals(dim)) return "trip_distance_bucket";
        if ("pickup_zone".equals(dim)) return "pickup_zone_bucket";
        return dim + "_bucket";
    }

    private InterpretationCandidatePlan plan(
            String label, String desc, String metric, String metricLabel,
            String secondary, String grouping, AggregationType agg,
            AnalyticalIntentType intent, double confidence, String source
    ) {
        return new InterpretationCandidatePlan(
                label, desc, metric, metricLabel, secondary,
                grouping != null ? grouping : "", agg, intent, confidence, source);
    }
}
