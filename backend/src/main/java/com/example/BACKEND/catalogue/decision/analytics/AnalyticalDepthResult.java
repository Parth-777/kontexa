package com.example.BACKEND.catalogue.decision.analytics;

import java.util.List;
import java.util.Map;

/**
 * Aggregated output of the {@link AnalyticalDepthEngine}.
 *
 * Contains all structurally-derived findings computed from raw warehouse rows
 * BEFORE evidence assembly. These findings are passed to the synthesis prompt
 * so the LLM receives pre-computed non-obvious structures — not just aggregate metrics.
 *
 * Each sub-result may be empty if the data did not support that analysis.
 * The prompt transformer renders only non-empty sections.
 */
public record AnalyticalDepthResult(
        List<SegmentBucket>       segmentBuckets,
        List<RelationshipSignal>  relationships,
        List<EfficiencyMetric>    efficiencyMetrics,
        DistributionProfile       distributionProfile,
        List<InflectionPoint>     inflectionPoints,
        List<CompositeScore>      compositeScores,
        List<String>              nonObviousInsights
) {

    // ─── SegmentBucket ───────────────────────────────────────────────────

    /**
     * A bucket of rows grouped by a dimension range.
     * Used for "does behavior differ across segments?" analysis.
     */
    public record SegmentBucket(
            String dimensionKey,
            String bucketLabel,
            double rangeMin,
            double rangeMax,
            double avgValue,
            double totalValue,
            long   count,
            double sharePercent,
            String characterization   // e.g. "HIGH_EFFICIENCY", "BELOW_AVERAGE"
    ) {}

    // ─── RelationshipSignal ──────────────────────────────────────────────

    /**
     * A detected relationship between two variables.
     * Correlation strength and direction, not causality.
     */
    public record RelationshipSignal(
            String dim1,
            String dim2,
            double correlationCoefficient,  // -1.0 to 1.0
            String direction,               // POSITIVE / NEGATIVE / NONLINEAR / WEAK
            String characterization,        // e.g. "strongly co-moving", "diminishing returns"
            double confidence
    ) {}

    // ─── EfficiencyMetric ────────────────────────────────────────────────

    /**
     * A derived efficiency ratio — value per unit of activity.
     */
    public record EfficiencyMetric(
            String entityKey,
            String ratioLabel,             // e.g. "revenue_per_trip", "revenue_per_mile"
            double value,
            double peerAverage,
            double percentile,
            String tier                    // TOP / ABOVE_AVERAGE / AVERAGE / BELOW_AVERAGE
    ) {}

    // ─── DistributionProfile ────────────────────────────────────────────

    /**
     * Statistical profile of a metric's distribution.
     * Moves beyond averages to expose concentration and skew.
     */
    public record DistributionProfile(
            String metricKey,
            double mean,
            double median,
            double stdDev,
            double skewness,
            double top10SharePercent,
            double bottom50SharePercent,
            double concentrationIndex,    // 0=perfectly equal, 1=fully concentrated
            String character              // e.g. "HIGHLY_CONCENTRATED", "NORMALLY_DISTRIBUTED"
    ) {}

    // ─── InflectionPoint ────────────────────────────────────────────────

    /**
     * A detected threshold where behavior changes.
     * Example: "efficiency increases to 8 miles then declines."
     */
    public record InflectionPoint(
            String dimensionKey,
            double thresholdValue,
            String behaviorBelow,
            String behaviorAbove,
            double preThresholdRate,
            double postThresholdRate,
            String implication
    ) {}

    // ─── CompositeScore ──────────────────────────────────────────────────

    /**
     * A multi-metric composite score for a single entity.
     * Combines dimensions intelligently for strategic ranking.
     */
    public record CompositeScore(
            String              entityKey,
            double              compositeScore,
            Map<String, Double> dimensionScores,
            String              tier,              // LEADER / MID_TIER / UNDERPERFORMER / AT_RISK
            List<String>        strengths,
            List<String>        weaknesses
    ) {}

    // ─── empty factory ───────────────────────────────────────────────────

    public static AnalyticalDepthResult empty() {
        return new AnalyticalDepthResult(
                List.of(), List.of(), List.of(),
                null, List.of(), List.of(), List.of()
        );
    }

    public boolean hasContent() {
        return !segmentBuckets.isEmpty()
                || !relationships.isEmpty()
                || !efficiencyMetrics.isEmpty()
                || distributionProfile != null
                || !inflectionPoints.isEmpty()
                || !compositeScores.isEmpty()
                || !nonObviousInsights.isEmpty();
    }
}
