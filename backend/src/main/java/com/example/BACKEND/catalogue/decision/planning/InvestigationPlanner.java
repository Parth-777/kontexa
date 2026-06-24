package com.example.BACKEND.catalogue.decision.planning;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Constructs an ordered set of analytical investigation steps for a given intent type.
 *
 * Steps represent analytical operations — not SQL queries. They define the
 * intellectual process the system will follow to answer the question rigorously,
 * which then maps to metric pack execution and evidence assembly.
 *
 * The investigation design mirrors how a senior analyst would plan the work:
 *   1. Establish baseline facts
 *   2. Build comparative context
 *   3. Identify outliers and concentrations
 *   4. Synthesise into weighted conclusions
 */
@Component
public class InvestigationPlanner {

    public List<InvestigationStep> plan(AnalyticalIntentType intentType) {
        return switch (intentType) {

            case CONTRIBUTION -> List.of(
                    step(1, "COMPUTE_SEGMENT_VALUE",
                            "Establish the absolute value of the segment in question",
                            List.of("segment_total_value"), ComparativeStrategy.ENTITY_DELTA, true),
                    step(2, "COMPUTE_TOTAL_VALUE",
                            "Establish the denominator — total across all segments",
                            List.of("overall_total_value"), ComparativeStrategy.ENTITY_DELTA, true),
                    step(3, "COMPUTE_SHARE_PERCENTAGE",
                            "Calculate the segment's share as a percentage of total",
                            List.of("segment_share"), ComparativeStrategy.VS_PEER_AVERAGE, true),
                    step(4, "COMPARE_AGAINST_EQUAL_SHARE",
                            "Compare actual share against proportional baseline to assess over/under-indexing",
                            List.of("segment_volume", "overall_volume"), ComparativeStrategy.VS_PEER_AVERAGE, false)
            );

            case RANKING -> List.of(
                    step(1, "COMPUTE_PRIMARY_AGGREGATE",
                            "Compute the primary ranking metric for all entities",
                            List.of("total_value"), ComparativeStrategy.PERCENTILE_RANK, true),
                    step(2, "COMPUTE_EFFICIENCY_RATIO",
                            "Compute value-per-unit to separate scale from quality",
                            List.of("efficiency_ratio"), ComparativeStrategy.VS_PEER_AVERAGE, true),
                    step(3, "COMPUTE_PERCENTILE_DISTRIBUTION",
                            "Position each entity in the distribution — top/bottom quartile boundaries",
                            List.of("total_value", "efficiency_ratio"), ComparativeStrategy.PERCENTILE_RANK, true),
                    step(4, "IDENTIFY_CONCENTRATION_RISK",
                            "Flag entities representing >25% of total — dependency risk signal",
                            List.of("concentration_share"), ComparativeStrategy.CONCENTRATION_RATIO, false),
                    step(5, "COMPUTE_GROWTH_SIGNAL",
                            "Layer in trend direction to identify momentum leaders vs laggards",
                            List.of("growth_indicator"), ComparativeStrategy.PERIOD_OVER_PERIOD, false)
            );

            case STRATEGIC_PRIORITIZATION -> List.of(
                    step(1, "COMPUTE_VALUE_DIMENSION",
                            "Measure total absolute contribution — the scale anchor",
                            List.of("total_value"), ComparativeStrategy.PERCENTILE_RANK, true),
                    step(2, "COMPUTE_EFFICIENCY_DIMENSION",
                            "Measure value per unit of activity — quality over quantity",
                            List.of("efficiency_ratio"), ComparativeStrategy.VS_PEER_AVERAGE, true),
                    step(3, "COMPUTE_MOMENTUM_DIMENSION",
                            "Measure growth rate — trajectory and acceleration",
                            List.of("growth_rate"), ComparativeStrategy.PERIOD_OVER_PERIOD, true),
                    step(4, "COMPUTE_CONCENTRATION_RISK",
                            "Measure dependency risk — how much of the total this entity represents",
                            List.of("concentration_share"), ComparativeStrategy.CONCENTRATION_RATIO, false),
                    step(5, "COMPUTE_CONSISTENCY_DIMENSION",
                            "Measure stability — variance across sub-periods",
                            List.of("consistency_score"), ComparativeStrategy.VS_HISTORICAL_BASELINE, false),
                    step(6, "BUILD_WEIGHTED_COMPOSITE_SCORE",
                            "Normalise all dimensions and apply strategic weights for final ranking",
                            List.of("total_value", "efficiency_ratio", "growth_rate"),
                            ComparativeStrategy.WEIGHTED_COMPOSITE_SCORE, true),
                    step(7, "IDENTIFY_STRATEGIC_CLUSTERS",
                            "Group into strategic tiers: leaders, mid-tier, underperformers, at-risk",
                            List.of("total_value", "efficiency_ratio"),
                            ComparativeStrategy.PERCENTILE_RANK, false)
            );

            case SEGMENTATION -> List.of(
                    step(1, "COMPUTE_SEGMENT_AGGREGATES",
                            "Compute value and volume for each segment independently",
                            List.of("segment_total_value", "segment_volume"),
                            ComparativeStrategy.VS_PEER_AVERAGE, true),
                    step(2, "COMPUTE_SEGMENT_SHARES",
                            "Express each segment as a percentage of total",
                            List.of("segment_share"), ComparativeStrategy.VS_PEER_AVERAGE, true),
                    step(3, "RANK_SEGMENTS_BY_VALUE",
                            "Order segments from highest to lowest contribution",
                            List.of("segment_total_value"), ComparativeStrategy.PERCENTILE_RANK, true),
                    step(4, "FLAG_CONCENTRATION",
                            "Identify segments with disproportionately high share — dependency risk",
                            List.of("segment_share"), ComparativeStrategy.CONCENTRATION_RATIO, false)
            );

            case COMPARISON -> List.of(
                    step(1, "ESTABLISH_CURRENT_VALUES",
                            "Compute the metric for the current period or entity",
                            List.of("current_period_value"), ComparativeStrategy.ENTITY_DELTA, true),
                    step(2, "ESTABLISH_REFERENCE_VALUES",
                            "Compute the metric for the reference period or comparison entity",
                            List.of("reference_period_value"), ComparativeStrategy.ENTITY_DELTA, true),
                    step(3, "COMPUTE_DELTA",
                            "Calculate absolute and percentage change between current and reference",
                            List.of("delta_absolute", "delta_percentage"),
                            ComparativeStrategy.PERIOD_OVER_PERIOD, true),
                    step(4, "CONTEXTUALISE_AGAINST_BASELINE",
                            "Assess whether the delta is within historical normal variance",
                            List.of("historical_baseline"), ComparativeStrategy.VS_HISTORICAL_BASELINE, false)
            );

            case ANOMALY_DETECTION -> List.of(
                    step(1, "COMPUTE_CURRENT_VALUES",
                            "Measure current metric values for all relevant entities",
                            List.of("current_period_value"), ComparativeStrategy.Z_SCORE_DEVIATION, true),
                    step(2, "ESTABLISH_HISTORICAL_BASELINE",
                            "Compute expected baseline from historical averages",
                            List.of("historical_baseline"), ComparativeStrategy.VS_HISTORICAL_BASELINE, true),
                    step(3, "COMPUTE_DEVIATION",
                            "Calculate z-score deviation for each entity",
                            List.of("deviation_magnitude"), ComparativeStrategy.Z_SCORE_DEVIATION, true),
                    step(4, "IDENTIFY_AFFECTED_SEGMENTS",
                            "Determine which sub-groups are driving the anomalous reading",
                            List.of("affected_segments"), ComparativeStrategy.CONCENTRATION_RATIO, true),
                    step(5, "ASSESS_PERSISTENCE",
                            "Check how many consecutive periods the anomaly has been present",
                            List.of("persistence_window"), ComparativeStrategy.VS_HISTORICAL_BASELINE, false)
            );

            case TREND_ANALYSIS -> List.of(
                    step(1, "COMPUTE_TIME_SERIES",
                            "Retrieve the metric across all available time periods",
                            List.of("time_series_values"), ComparativeStrategy.PERIOD_OVER_PERIOD, true),
                    step(2, "COMPUTE_PERIOD_DELTAS",
                            "Calculate change between each consecutive period",
                            List.of("period_over_period_delta"), ComparativeStrategy.PERIOD_OVER_PERIOD, true),
                    step(3, "COMPUTE_GROWTH_RATE",
                            "Calculate percentage growth per period",
                            List.of("growth_rate"), ComparativeStrategy.YEAR_OVER_YEAR, true),
                    step(4, "DETECT_ACCELERATION_INFLECTION",
                            "Identify whether growth is accelerating, stable, or decelerating",
                            List.of("acceleration"), ComparativeStrategy.PERIOD_OVER_PERIOD, false)
            );

            case ROOT_CAUSE_INVESTIGATION -> List.of(
                    step(1, "IDENTIFY_PRIMARY_CHANGE",
                            "Quantify the total change in the metric under investigation",
                            List.of("primary_metric_change"), ComparativeStrategy.PERIOD_OVER_PERIOD, true),
                    step(2, "DECOMPOSE_BY_SEGMENT",
                            "Attribute the total change to each segment — contribution analysis",
                            List.of("segment_contribution_to_change"),
                            ComparativeStrategy.CONCENTRATION_RATIO, true),
                    step(3, "SEPARATE_VOLUME_VS_RATE_EFFECT",
                            "Determine how much of the change is from volume vs rate/price per unit",
                            List.of("volume_component", "rate_component"),
                            ComparativeStrategy.ENTITY_DELTA, true),
                    step(4, "IDENTIFY_DOMINANT_DRIVER",
                            "Surface the single segment or factor explaining the majority of the delta",
                            List.of("dominant_driver_segment"), ComparativeStrategy.CONCENTRATION_RATIO, true),
                    step(5, "ASSESS_BREADTH",
                            "Determine if the change is concentrated (localised) or broad (systemic)",
                            List.of("affected_segments"), ComparativeStrategy.Z_SCORE_DEVIATION, false)
            );

            case FORECASTING -> List.of(
                    step(1, "EXTRACT_RECENT_TREND",
                            "Compute the observed growth rate from the last N periods",
                            List.of("recent_trend_values", "growth_rate"),
                            ComparativeStrategy.PERIOD_OVER_PERIOD, true),
                    step(2, "COMPUTE_VOLATILITY",
                            "Measure historical variance in growth rate for confidence interval",
                            List.of("volatility"), ComparativeStrategy.VS_HISTORICAL_BASELINE, true),
                    step(3, "PROJECT_FORWARD",
                            "Apply observed growth rate to project next period estimate",
                            List.of("growth_rate"), ComparativeStrategy.VS_HISTORICAL_BASELINE, true)
            );

            default -> List.of(
                    step(1, "COMPUTE_PRIMARY_METRICS",
                            "Compute the main aggregate metrics for the question",
                            List.of("total_value", "volume"), ComparativeStrategy.VS_PEER_AVERAGE, true),
                    step(2, "COMPARE_AGAINST_AVERAGE",
                            "Position results against peer average for relative context",
                            List.of("total_value"), ComparativeStrategy.PERCENTILE_RANK, false)
            );
        };
    }

    public PlanDepth depthFor(AnalyticalIntentType intentType) {
        return switch (intentType.canonical()) {
            case CONTRIBUTION, COMPARISON, COMPOSITION -> PlanDepth.MINIMAL;
            case RANKING, DISTRIBUTION, SEGMENTATION,
                 TREND_ANALYSIS, FORECASTING, EFFICIENCY,
                 CORRELATION                         -> PlanDepth.STANDARD;
            case STRATEGIC_PRIORITIZATION,
                 ANOMALY_DETECTION,
                 ROOT_CAUSE_INVESTIGATION,
                 RETENTION                           -> PlanDepth.DEEP;
            default                                    -> PlanDepth.STANDARD;
        };
    }

    private static InvestigationStep step(int n, String op, String purpose,
                                          List<String> metrics,
                                          ComparativeStrategy strategy, boolean required) {
        return new InvestigationStep(n, op, purpose, metrics, strategy, required);
    }
}
