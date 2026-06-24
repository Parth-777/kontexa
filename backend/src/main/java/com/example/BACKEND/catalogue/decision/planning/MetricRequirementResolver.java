package com.example.BACKEND.catalogue.decision.planning;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the set of analytical metrics required before answering a given intent.
 *
 * Metrics are expressed as semantic analytical concepts — NOT SQL column names.
 * The MetricPackPlanner and registry resolve them against the actual schema.
 *
 * Key principle: metrics are determined by the analytical objective,
 * not by keyword matching against the user's question.
 *
 * Each intent type has a canonical set of required measurements that enable
 * correct analytical reasoning.
 */
@Component
public class MetricRequirementResolver {

    public List<MetricRequirement> resolve(AnalyticalIntentType intentType) {
        return switch (intentType) {

            case CONTRIBUTION -> List.of(
                    MetricRequirement.critical("segment_total_value",
                            "Absolute value of the segment being asked about", "SUM"),
                    MetricRequirement.critical("overall_total_value",
                            "Total across all segments — denominator for share calculation", "SUM"),
                    MetricRequirement.important("segment_volume",
                            "Transaction/trip count for the segment", "COUNT"),
                    MetricRequirement.important("overall_volume",
                            "Total transaction/trip count — denominator for volume share", "COUNT")
            );

            case RANKING -> List.of(
                    MetricRequirement.critical("total_value",
                            "Primary ranking dimension — aggregate value per entity", "SUM"),
                    MetricRequirement.scored("efficiency_ratio",
                            "Value per unit of activity — captures quality, not just scale", "AVG"),
                    MetricRequirement.scored("volume",
                            "Activity volume — demand proxy", "COUNT"),
                    MetricRequirement.important("growth_indicator",
                            "Period change — separates momentum from stagnant performers", "DELTA")
            );

            case STRATEGIC_PRIORITIZATION -> List.of(
                    MetricRequirement.critical("total_value",
                            "Scale dimension — absolute revenue/output contribution", "SUM"),
                    MetricRequirement.scored("efficiency_ratio",
                            "Quality dimension — value generated per unit of activity", "AVG"),
                    MetricRequirement.scored("volume",
                            "Demand dimension — activity concentration", "COUNT"),
                    MetricRequirement.scored("growth_rate",
                            "Momentum dimension — trajectory and acceleration", "DELTA"),
                    MetricRequirement.important("concentration_share",
                            "Dependency risk — what fraction of total this entity represents", "RATIO"),
                    MetricRequirement.important("consistency_score",
                            "Stability dimension — variance across sub-periods", "STDDEV")
            );

            case DISTRIBUTION, SEGMENTATION -> List.of(
                    MetricRequirement.critical("segment_total_value",
                            "Absolute value per segment", "SUM"),
                    MetricRequirement.critical("segment_volume",
                            "Activity count per segment", "COUNT"),
                    MetricRequirement.important("segment_share",
                            "Segment as percentage of total", "RATIO"),
                    MetricRequirement.important("distribution_profile",
                            "Skew, variance, and spread characterisation", "STDDEV")
            );

            case COMPOSITION -> List.of(
                    MetricRequirement.critical("segment_total_value",
                            "Absolute value per portfolio segment", "SUM"),
                    MetricRequirement.critical("segment_share",
                            "Mix percentage of each segment", "RATIO"),
                    MetricRequirement.important("dominant_mix",
                            "Segments defining portfolio composition", "RATIO")
            );

            case EFFICIENCY -> List.of(
                    MetricRequirement.critical("total_value",
                            "Numerator — revenue or value generated", "SUM"),
                    MetricRequirement.critical("volume",
                            "Denominator — trips, miles, or activity units", "SUM"),
                    MetricRequirement.critical("efficiency_ratio",
                            "Value per unit of activity", "RATIO"),
                    MetricRequirement.important("efficiency_rank",
                            "Yield ranking across segments", "AVG")
            );

            case CORRELATION -> List.of(
                    MetricRequirement.critical("metric_pairs",
                            "Paired metric and driver dimensions", "SUM"),
                    MetricRequirement.critical("covariation_signal",
                            "Co-movement strength between variables", "AVG"),
                    MetricRequirement.important("relationship_rank",
                            "Ranked relationship strength", "RATIO")
            );

            case RETENTION -> List.of(
                    MetricRequirement.critical("cohort_definition",
                            "Cohort entry period", "COUNT"),
                    MetricRequirement.critical("retention_rate",
                            "Repeat engagement over subsequent periods", "RATIO"),
                    MetricRequirement.important("cohort_comparison",
                            "Retention curve comparison across cohorts", "RATIO")
            );

            case COMPARISON -> List.of(
                    MetricRequirement.critical("current_period_value",
                            "Current measurement for comparison", "SUM"),
                    MetricRequirement.critical("reference_period_value",
                            "Reference measurement — prior period or baseline", "SUM"),
                    MetricRequirement.important("delta_absolute",
                            "Absolute change between periods", "DELTA"),
                    MetricRequirement.important("delta_percentage",
                            "Percentage change — normalised for scale", "RATIO")
            );

            case ANOMALY_DETECTION -> List.of(
                    MetricRequirement.critical("current_period_value",
                            "Current measurement to assess for anomaly", "SUM"),
                    MetricRequirement.critical("historical_baseline",
                            "Expected value — historical average or prior trend", "AVG"),
                    MetricRequirement.critical("deviation_magnitude",
                            "How far current deviates from baseline", "DELTA"),
                    MetricRequirement.important("affected_segments",
                            "Which sub-groups are driving the anomaly", "COUNT"),
                    MetricRequirement.important("persistence_window",
                            "How many consecutive periods the anomaly has persisted", "COUNT")
            );

            case TREND_ANALYSIS -> List.of(
                    MetricRequirement.critical("time_series_values",
                            "Value at each time period for trajectory analysis", "SUM"),
                    MetricRequirement.important("period_over_period_delta",
                            "Change between consecutive periods", "DELTA"),
                    MetricRequirement.important("growth_rate",
                            "Percentage growth per period", "RATIO"),
                    MetricRequirement.important("acceleration",
                            "Change in growth rate — detecting momentum shifts", "DELTA")
            );

            case ROOT_CAUSE_INVESTIGATION -> List.of(
                    MetricRequirement.critical("primary_metric_change",
                            "The metric that changed — the observation being explained", "DELTA"),
                    MetricRequirement.critical("segment_contribution_to_change",
                            "How much each segment contributed to the total change", "DELTA"),
                    MetricRequirement.important("volume_component",
                            "How much of the change is explained by volume", "COUNT"),
                    MetricRequirement.important("rate_component",
                            "How much of the change is explained by rate/price per unit", "AVG"),
                    MetricRequirement.important("dominant_driver_segment",
                            "Which single segment accounts for the majority of the change", "RATIO")
            );

            case FORECASTING -> List.of(
                    MetricRequirement.critical("recent_trend_values",
                            "Last N periods of actuals to project from", "SUM"),
                    MetricRequirement.important("growth_rate",
                            "Average rate of change as projection basis", "RATIO"),
                    MetricRequirement.important("volatility",
                            "Standard deviation of growth rate — confidence interval basis", "STDDEV")
            );

            default -> List.of(
                    MetricRequirement.critical("total_value",
                            "Primary aggregate metric", "SUM"),
                    MetricRequirement.important("volume",
                            "Activity count", "COUNT")
            );
        };
    }

    public List<String> metricKeys(AnalyticalIntentType intentType) {
        List<String> keys = new ArrayList<>();
        for (MetricRequirement req : resolve(intentType)) {
            keys.add(req.metricKey());
        }
        return keys;
    }
}
