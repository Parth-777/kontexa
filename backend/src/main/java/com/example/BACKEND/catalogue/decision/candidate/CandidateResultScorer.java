package com.example.BACKEND.catalogue.decision.candidate;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedGroupEntry;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scores executed candidate outputs — higher score = stronger evidence for selection.
 */
@Component
public class CandidateResultScorer {

    public record ScoredCandidate(
            AnalyticalCandidate      candidate,
            MaterializedQueryResult  result,
            double                   totalScore,
            double                   rowScore,
            double                   varianceScore,
            double                   concentrationScore,
            double                   spreadScore,
            double                   chartabilityScore
    ) {}

    public ScoredCandidate score(AnalyticalCandidate candidate, MaterializedQueryResult result) {
        return score(candidate, result, null);
    }

    public ScoredCandidate score(
            AnalyticalCandidate candidate, MaterializedQueryResult result, MetricResolution expected
    ) {
        if (result == null || !result.hasContent()) {
            return new ScoredCandidate(candidate, result, 0, 0, 0, 0, 0, 0);
        }

        MaterializedGrouping g = result.primaryGrouping();
        List<MaterializedGroupEntry> entries = g.rankedEntries();

        double rowScore = scoreRows(result.totalRows(), entries.size());
        double varianceScore = scoreVariance(entries);
        double concentrationScore = scoreConcentration(g, entries);
        double spreadScore = scoreSpread(entries);
        double validityScore = scoreAggregationValidity(candidate);
        double chartabilityScore = scoreChartability(entries);
        double nonZeroScore = scoreNonZero(entries);

        double semanticFit = scoreSemanticFit(candidate, expected);
        double sourceBonus = scoreSource(candidate.plan().source());

        double total = rowScore * 0.12
                + varianceScore * 0.15
                + concentrationScore * 0.12
                + spreadScore * 0.12
                + validityScore * 0.08
                + chartabilityScore * 0.12
                + nonZeroScore * 0.08
                + candidate.plan().confidence() * 0.11
                + semanticFit * 0.15
                + sourceBonus;

        return new ScoredCandidate(
                candidate, result, total,
                rowScore, varianceScore, concentrationScore, spreadScore, chartabilityScore);
    }

    private double scoreRows(int totalRows, int groupCount) {
        if (totalRows <= 0 || groupCount < 2) return 0;
        double rowFactor = Math.min(1.0, totalRows / 30.0);
        double groupFactor = Math.min(1.0, groupCount / 6.0);
        return rowFactor * 0.5 + groupFactor * 0.5;
    }

    private double scoreVariance(List<MaterializedGroupEntry> entries) {
        if (entries.size() < 2) return 0;
        double[] values = entries.stream().mapToDouble(MaterializedGroupEntry::totalValue).toArray();
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.length;
        if (mean <= 0) return 0;
        double cv = 0;
        for (double v : values) cv += (v - mean) * (v - mean);
        cv = Math.sqrt(cv / values.length) / mean;
        return Math.min(1.0, cv / 0.5);
    }

    private double scoreConcentration(MaterializedGrouping g, List<MaterializedGroupEntry> entries) {
        if (entries.isEmpty()) return 0;
        double topShare = entries.getFirst().sharePct();
        double gini = g.giniConcentration();
        if (topShare > 95 && entries.size() < 3) return 0.2;
        double signal = Math.min(1.0, topShare / 50.0) * 0.6 + Math.min(1.0, gini) * 0.4;
        return Math.max(0.1, signal);
    }

    private double scoreSpread(List<MaterializedGroupEntry> entries) {
        if (entries.size() < 2) return 0;
        double top = entries.getFirst().totalValue();
        double bottom = entries.getLast().totalValue();
        if (bottom <= 0) return top > 0 ? 0.8 : 0;
        double ratio = top / bottom;
        return Math.min(1.0, (ratio - 1.0) / 4.0);
    }

    private double scoreAggregationValidity(AnalyticalCandidate candidate) {
        AggregationType agg = candidate.plan().aggregation();
        String metric = candidate.plan().primaryMetric();
        if (agg == AggregationType.RATIO && metric != null && metric.contains("per")) return 0.7;
        if (agg == AggregationType.SUM || agg == AggregationType.AVG) return 1.0;
        return 0.8;
    }

    private double scoreChartability(List<MaterializedGroupEntry> entries) {
        int n = entries.size();
        if (n < 2) return 0;
        if (n >= 3 && n <= 12) return 1.0;
        if (n == 2) return 0.7;
        return 0.5;
    }

    private double scoreNonZero(List<MaterializedGroupEntry> entries) {
        if (entries.isEmpty()) return 0;
        long nonZero = entries.stream().filter(e -> e.totalValue() > 0).count();
        return (double) nonZero / entries.size();
    }

    private double scoreSemanticFit(AnalyticalCandidate candidate, MetricResolution expected) {
        if (expected == null || !expected.isUsable()) return 0.5;
        double fit = 0;
        String metric = candidate.plan().primaryMetric();
        if (metric != null && expected.primaryMetric() != null
                && metric.equalsIgnoreCase(expected.primaryMetric())) {
            fit += 0.5;
        }
        String dim = candidate.dimensionColumn();
        String grouping = candidate.plan().grouping();
        if (expected.dimension() != null) {
            if (expected.dimension().equalsIgnoreCase(dim)
                    || expected.dimension().equalsIgnoreCase(grouping)
                    || (grouping != null && grouping.contains(expected.dimension()))) {
                fit += 0.5;
            } else if (dim != null && dim.contains("distance")
                    && !expected.dimension().contains("distance")) {
                fit -= 0.6;
            }
        } else if (expected.grouping() != null && "composition".equals(expected.grouping())) {
            if (grouping == null || grouping.isBlank() || "composition".equals(grouping)) fit += 0.4;
        }
        return Math.max(0, Math.min(1.0, fit));
    }

    private double scoreSource(String source) {
        if (source == null) return 0;
        return switch (source) {
            case "reasoning_planner", "semantic_extractor" -> 0.25;
            case "weekend_heuristic", "tip_share_heuristic" -> 0.15;
            case "distance_heuristic", "default_exploration", "fallback_dictionary" -> -0.20;
            default -> 0;
        };
    }
}
