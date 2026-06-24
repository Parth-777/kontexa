package com.example.BACKEND.catalogue.decision.planning;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the {@link ComparativeFramework} for a given analytical intent.
 *
 * The framework specifies exactly HOW results should be compared once
 * warehouse metrics are computed. It governs the comparative reasoning layer
 * and the synthesis framing.
 *
 * Key principle: comparative context is not optional.
 * Executives always need numbers in context — not in isolation.
 * Every analytical intent has a natural comparative frame.
 */
@Component
public class ComparativeFrameworkBuilder {

    public ComparativeFramework build(AnalyticalIntentType intentType) {
        return switch (intentType) {

            case CONTRIBUTION -> new ComparativeFramework(
                    List.of(ComparativeStrategy.VS_PEER_AVERAGE,
                            ComparativeStrategy.PERCENTILE_RANK),
                    "Express as percentage share of total and compare against equal-share baseline.",
                    "Absolute value and percentage share, ranked highest to lowest.",
                    true,
                    "Frame as: 'X contributes Y% of total Z, vs equal-share baseline of N%. " +
                    "This [exceeds/falls below] proportional expectation by [delta]%.'"
            );

            case RANKING -> new ComparativeFramework(
                    List.of(ComparativeStrategy.PERCENTILE_RANK,
                            ComparativeStrategy.VS_PEER_AVERAGE,
                            ComparativeStrategy.CONCENTRATION_RATIO),
                    "Rank entities by primary metric. Normalise to percentile for relative positioning.",
                    "Highest to lowest on primary metric with peer comparison context.",
                    true,
                    "Frame as: '#1 performer is X times the average. Bottom quartile performs at Y% of median.'"
            );

            case STRATEGIC_PRIORITIZATION -> new ComparativeFramework(
                    List.of(ComparativeStrategy.WEIGHTED_COMPOSITE_SCORE,
                            ComparativeStrategy.PERCENTILE_RANK,
                            ComparativeStrategy.VS_PEER_AVERAGE,
                            ComparativeStrategy.CONCENTRATION_RATIO),
                    "Compute multi-dimensional composite score. Normalise each dimension before weighting.",
                    "Composite strategic score — weighted across value, efficiency, growth, and risk.",
                    true,
                    "Frame as: strategic tiers (top quartile, mid-tier, underperformers). " +
                    "Flag entities with high value but high concentration risk separately."
            );

            case DISTRIBUTION, SEGMENTATION -> new ComparativeFramework(
                    List.of(ComparativeStrategy.VS_PEER_AVERAGE,
                            ComparativeStrategy.CONCENTRATION_RATIO,
                            ComparativeStrategy.PERCENTILE_RANK),
                    "Compare dominant vs weak buckets; characterise skew and spread.",
                    "Ranked by bucket share with variance and outlier flagging.",
                    true,
                    "Frame as: which buckets dominate, which form the long tail, and whether distribution is skewed."
            );

            case COMPOSITION -> new ComparativeFramework(
                    List.of(ComparativeStrategy.CONCENTRATION_RATIO,
                            ComparativeStrategy.VS_PEER_AVERAGE),
                    "Express portfolio mix as share of total; compare vs equal-weight baseline.",
                    "Ranked by mix share — dominant segments first.",
                    true,
                    "Frame as: what defines the portfolio mix and which segments are over-represented."
            );

            case EFFICIENCY -> new ComparativeFramework(
                    List.of(ComparativeStrategy.VS_PEER_AVERAGE,
                            ComparativeStrategy.PERCENTILE_RANK),
                    "Rank by yield per unit, not absolute scale.",
                    "Highest to lowest efficiency ratio with peer comparison.",
                    true,
                    "Frame as: who delivers the best unit economics and how wide the efficiency spread is."
            );

            case CORRELATION -> new ComparativeFramework(
                    List.of(ComparativeStrategy.VS_PEER_AVERAGE),
                    "Rank relationships by co-movement strength.",
                    "Strongest positive and negative associations first.",
                    false,
                    "Frame as: which variables move together and whether the relationship is material."
            );

            case RETENTION -> new ComparativeFramework(
                    List.of(ComparativeStrategy.PERIOD_OVER_PERIOD,
                            ComparativeStrategy.VS_HISTORICAL_BASELINE),
                    "Compare retention curves across cohorts and periods.",
                    "Chronological cohort retention rates.",
                    false,
                    "Frame as: how repeat engagement evolves and which cohorts retain best."
            );

            case COMPARISON -> new ComparativeFramework(
                    List.of(ComparativeStrategy.PERIOD_OVER_PERIOD,
                            ComparativeStrategy.YEAR_OVER_YEAR,
                            ComparativeStrategy.ENTITY_DELTA),
                    "Express change as both absolute and percentage delta.",
                    "Delta ranking — entities ordered by magnitude of change.",
                    false,
                    "Always state: current value, reference value, absolute change, percentage change. " +
                    "Context: is this change within historical normal range?"
            );

            case ANOMALY_DETECTION -> new ComparativeFramework(
                    List.of(ComparativeStrategy.Z_SCORE_DEVIATION,
                            ComparativeStrategy.VS_HISTORICAL_BASELINE,
                            ComparativeStrategy.PERIOD_OVER_PERIOD),
                    "Compute deviation as z-score. Flag anything beyond ±2σ as anomalous.",
                    "Ordered by deviation severity — most anomalous first.",
                    false,
                    "Frame as: 'Current value deviates X standard deviations from historical mean. " +
                    "This is [within/outside] the normal range of [baseline range].'"
            );

            case TREND_ANALYSIS -> new ComparativeFramework(
                    List.of(ComparativeStrategy.PERIOD_OVER_PERIOD,
                            ComparativeStrategy.YEAR_OVER_YEAR,
                            ComparativeStrategy.VS_HISTORICAL_BASELINE),
                    "Show growth rate per period and overall trajectory direction.",
                    "Chronological — focus on acceleration, deceleration, and inflection points.",
                    false,
                    "Frame as: rate of change (growing/declining at X% per period), not just direction. " +
                    "Identify if growth is accelerating or decelerating."
            );

            case ROOT_CAUSE_INVESTIGATION -> new ComparativeFramework(
                    List.of(ComparativeStrategy.PERIOD_OVER_PERIOD,
                            ComparativeStrategy.Z_SCORE_DEVIATION,
                            ComparativeStrategy.CONCENTRATION_RATIO),
                    "Decompose change into volume component and rate/price component.",
                    "Segment contribution to total change — which segments explain most of the delta.",
                    true,
                    "Frame as: what percentage of total change is explained by each factor. " +
                    "Lead with the dominant contributor. Frame causality as 'data suggests' not 'X caused Y'."
            );

            case FORECASTING -> new ComparativeFramework(
                    List.of(ComparativeStrategy.PERIOD_OVER_PERIOD,
                            ComparativeStrategy.VS_HISTORICAL_BASELINE),
                    "Project forward using average growth rate with confidence interval from volatility.",
                    "Projected value with upper/lower band based on historical variance.",
                    false,
                    "Frame as: 'Based on observed [N]-period trend of X% per period, projected value is Y. " +
                    "Given historical volatility, this carries meaningful uncertainty.'"
            );

            default -> new ComparativeFramework(
                    List.of(ComparativeStrategy.VS_PEER_AVERAGE,
                            ComparativeStrategy.PERCENTILE_RANK),
                    "Normalise to peer average. Express as percentile where possible.",
                    "Primary metric ranked highest to lowest.",
                    false,
                    "Compare each finding against the peer average and distribution median."
            );
        };
    }
}
