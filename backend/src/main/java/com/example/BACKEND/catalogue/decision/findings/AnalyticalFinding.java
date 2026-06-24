package com.example.BACKEND.catalogue.decision.findings;

import java.util.List;

/**
 * Sealed hierarchy of typed analytical findings.
 *
 * Each subtype represents one class of analytical discovery derived from pre-computed
 * warehouse evidence.  The LLM receives these typed findings — NOT raw metrics.
 *
 * The magnitude() method returns the single most important number in each finding,
 * used for ordering findings by analytical signal strength.
 */
public sealed interface AnalyticalFinding
        permits AnalyticalFinding.ContributionFinding,
                AnalyticalFinding.RankingFinding,
                AnalyticalFinding.EfficiencyFinding,
                AnalyticalFinding.TemporalPatternFinding,
                AnalyticalFinding.ComparativeFinding,
                AnalyticalFinding.CorrelationFinding {

    FindingType findingType();
    double      magnitude();

    // ─── 1. CONTRIBUTION_BREAKDOWN ───────────────────────────────────────

    /**
     * Shows how a set of segments partition a total.
     *
     * Example: "Weekend transactions = 24.5% of total revenue"
     * Includes: top contributor, concentration ratio, Gini, gap between segments.
     */
    record ContributionFinding(
            String               dimensionLabel,     // grouping dimension — never a metric
            List<Segment>        segments,           // all segments, ranked by value desc
            String               topContributor,
            double               topContributorSharePct,
            double               concentrationRatio, // top-3 share as %
            double               giniCoefficient,    // 0=equal, 1=fully concentrated
            double               leaderToTailRatio,  // top/bottom value multiple
            String               executiveSummary,   // pre-computed one-liner
            String               metricLabel        // value being distributed (e.g. Revenue)
    ) implements AnalyticalFinding {
        public FindingType findingType() { return FindingType.CONTRIBUTION_BREAKDOWN; }
        public double magnitude()        { return topContributorSharePct; }

        public record Segment(
                String name,
                double value,
                double sharePct,
                int    rank,
                String tier    // DOMINANT (>30%) / SIGNIFICANT (10-30%) / MINOR (<10%)
        ) {}
    }

    // ─── 2. RANKING_FINDING ──────────────────────────────────────────────

    /**
     * A ranked list of entities on a single metric.
     *
     * Example: "Hour 18 generates 2.4x the average hourly revenue (rank #1)"
     * Includes: leader, percentile, multiplier vs average, performance gap.
     */
    record RankingFinding(
            String             metricLabel,       // what was ranked on
            String             groupingLabel,     // what was ranked
            List<RankedEntity> rankedEntities,
            double             leaderValue,
            double             medianValue,
            double             tailValue,
            double             leaderToMedianMultiple,
            double             leaderToTailMultiple,
            double             peerAverage,
            String             executiveSummary
    ) implements AnalyticalFinding {
        public FindingType findingType() { return FindingType.RANKING; }
        public double magnitude()        { return leaderToTailMultiple; }

        public record RankedEntity(
                String name,
                double value,
                int    rank,
                double percentileRank,
                double multiplierVsAvg,
                double gapToLeader,        // absolute delta to #1
                double gapToLeaderPct,     // % delta to #1
                String tier
        ) {}
    }

    // ─── 3. EFFICIENCY_FINDING ───────────────────────────────────────────

    /**
     * Compares entities on a value-per-unit ratio (efficiency).
     *
     * Example: "Category A generates 3.2x revenue per transaction vs Category C"
     * Includes: ratio, peer comparison, efficiency tier, spread.
     */
    record EfficiencyFinding(
            String              groupingLabel,
            String              numeratorLabel,   // e.g. "Revenue"
            String              denominatorLabel, // e.g. "Trips"
            List<EfficiencyEntry> entries,
            double              bestEfficiency,
            double              worstEfficiency,
            double              averageEfficiency,
            double              efficiencySpread, // best / worst ratio
            String              executiveSummary
    ) implements AnalyticalFinding {
        public FindingType findingType() { return FindingType.EFFICIENCY; }
        public double magnitude()        { return efficiencySpread; }

        public record EfficiencyEntry(
                String name,
                double numeratorTotal,
                double denominatorTotal,
                double efficiencyRatio,
                double deviationFromMean,  // (ratio - avg) / avg  (0 = exactly average)
                String tier               // ELITE / HIGH / AVERAGE / BELOW / POOR
        ) {}
    }

    // ─── 4. TEMPORAL_PATTERN_FINDING ─────────────────────────────────────

    /**
     * Identifies patterns, peaks, troughs, and momentum across time buckets.
     *
     * Example: "Peak activity at 18:00-19:00 (2.8x average); volatility = HIGH"
     * Includes: top/worst periods, volatility, momentum direction, inflections.
     */
    record TemporalPatternFinding(
            String                 temporalDimension,  // "Hour of Day", "Month", "Weekday"
            List<TemporalPeriod>   periods,            // all buckets, ordered chronologically
            String                 peakPeriod,
            double                 peakValue,
            String                 troughPeriod,
            double                 troughValue,
            double                 volatility,         // coefficient of variation (0-1)
            String                 momentum,           // RISING / FALLING / STABLE / VOLATILE
            List<InflectionPoint>  inflectionPoints,
            String                 executiveSummary
    ) implements AnalyticalFinding {
        public FindingType findingType() { return FindingType.TEMPORAL_PATTERN; }
        public double magnitude()        { return volatility; }

        public record TemporalPeriod(
                String  label,
                double  value,
                int     rank,
                boolean isPeak,
                boolean isTrough
        ) {}

        public record InflectionPoint(
                String fromPeriod,
                String toPeriod,
                double changeValue,
                double changePct,
                String direction   // SPIKE / DROP / TREND_SHIFT
        ) {}
    }

    // ─── 5. COMPARATIVE_FINDING ──────────────────────────────────────────

    /**
     * Direct comparison between two specific entities.
     *
     * Example: "Channel A leads Channel B by 2.1x ($1.2M vs $570K)"
     * Includes: absolute values, delta, delta%, direction, multiple.
     */
    record ComparativeFinding(
            String entityA,
            String entityB,
            double valueA,
            double valueB,
            double delta,          // valueA - valueB
            double deltaPct,       // (valueA - valueB) / valueB * 100
            String direction,      // A_LEADS / B_LEADS / PARITY
            double multiple,       // max(A,B) / min(A,B)
            String metricLabel,
            String executiveSummary
    ) implements AnalyticalFinding {
        public FindingType findingType() { return FindingType.COMPARATIVE; }
        public double magnitude()        { return Math.abs(deltaPct); }
    }

    // ─── 6. CORRELATION_FINDING ──────────────────────────────────────────

    /**
     * Pearson correlation between two metrics (CORR aggregate).
     */
    record CorrelationFinding(
            String sourceVariable,
            String targetVariable,
            double correlationCoefficient,
            long   sampleSize,
            String strength,
            String direction,
            String interpretation,
            String executiveSummary
    ) implements AnalyticalFinding {
        public FindingType findingType() { return FindingType.CORRELATION; }
        public double magnitude()        { return Math.abs(correlationCoefficient); }
    }
}
