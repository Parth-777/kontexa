package com.example.BACKEND.catalogue.decision.planning;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.IntentResolution;
import com.example.BACKEND.catalogue.decision.clarification.ResolvedAnalyticalQuestion;
import com.example.BACKEND.catalogue.decision.governance.DenominatorContext;
import com.example.BACKEND.catalogue.decision.governance.MetricDecompositionBinding;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticRegistry;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import com.example.BACKEND.catalogue.decision.semantic.ContributionAnalysisPlan;
import com.example.BACKEND.catalogue.decision.semantic.DimensionImpactPlan;
import com.example.BACKEND.catalogue.decision.semantic.SemanticAnalysisPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Question-aware analytical decomposition engine.
 *
 * For each classified intent, generates the required analytical steps that drive
 * the entire pipeline — not generic aggregation.
 */
@Component
public class QueryDecompositionEngine {

    private final DomainAnalyticalDefaults domainDefaults;
    private final MetricSemanticRegistry metricRegistry;

    public QueryDecompositionEngine(
            DomainAnalyticalDefaults domainDefaults,
            MetricSemanticRegistry metricRegistry
    ) {
        this.domainDefaults = domainDefaults;
        this.metricRegistry = metricRegistry;
    }

    public AnalyticalReasoningPlan decompose(IntentResolution intent, AnalyticalIntentType intentType) {
        DomainAnalyticalDefaults.DomainProfile domain = domainDefaults.resolve(
                intent.question(), null);

        if (intentType == AnalyticalIntentType.RETENTION
                && !domainDefaults.retentionSupported(domain, intentType)) {
            intentType = AnalyticalIntentType.DISTRIBUTION;
        }

        MetricDecompositionBinding binding = metricRegistry.bindForIntent(
                intent.question(), intentType, inferMetric(intent, domain), inferDimension(intent, domain, intentType));

        String metric = binding.metricLabel();
        String dimension = binding.groupingLabel();
        String comparison = comparisonMode(intentType);
        ChartSpec.ChartType chart = preferredChart(intentType);
        List<DecompositionStep> steps = stepsFor(intentType, dimension, metric, domain);
        String summary = buildSummary(intentType, metric, dimension, comparison, chart, steps.size())
                + " Binding: " + binding.metricColumn() + " " + binding.aggregation()
                + " by " + binding.groupingColumn() + ".";

        return new AnalyticalReasoningPlan(
                intentType, metric, dimension, comparison, chart, steps, domain, binding, summary);
    }

    /**
     * Project {@link AnalysisPlan} into an {@link AnalyticalReasoningPlan} without inference,
     * domain defaults, bucket rewriting, or semantic-parser overrides.
     */
    public AnalyticalReasoningPlan decomposeFromAnalysisPlan(AnalysisPlan contract) {
        if (contract == null) {
            throw new AnalysisPlanProjectionException("AnalysisPlan is required for decomposition projection");
        }
        if (contract.primaryMetric() == null || contract.primaryMetric().isBlank()) {
            throw new AnalysisPlanProjectionException(
                    "AnalysisPlan.primaryMetric insufficient for projection"
                            + (contract.blockingReasons() != null && !contract.blockingReasons().isEmpty()
                            ? ": " + String.join("; ", contract.blockingReasons()) : ""));
        }

        AnalyticalIntentType intentType = contract.intent().toAnalyticalIntentType();
        String metricColumn = contract.primaryMetric();
        String metricLabel = contract.primaryMetricLabel() != null && !contract.primaryMetricLabel().isBlank()
                ? contract.primaryMetricLabel() : metricColumn;
        String groupingColumn = AnalysisPlanProjection.groupingColumn(contract);
        String groupingLabel = AnalysisPlanProjection.groupingLabel(contract);
        AggregationType aggregation = AnalysisPlanProjection.aggregation(contract.intent());

        MetricDecompositionBinding binding = new MetricDecompositionBinding(
                metricColumn,
                metricLabel,
                aggregation,
                groupingColumn,
                groupingLabel,
                DenominatorContext.forContribution(metricLabel, groupingLabel, 0, 0, 0));

        AnalysisPlanProjectionVerifier.verify(contract, intentType, binding, aggregation);

        List<DecompositionStep> steps = stepsFor(
                intentType,
                groupingLabel != null && !groupingLabel.isBlank() ? groupingLabel : "segment",
                metricLabel != null && !metricLabel.isBlank() ? metricLabel : "metric",
                DomainAnalyticalDefaults.generic());
        String summary = String.format(
                "Projected from AnalysisPlan: %s %s by %s (%s).",
                intentType.name(), metricColumn, groupingColumn, aggregation.name());

        return new AnalyticalReasoningPlan(
                intentType,
                metricLabel,
                groupingLabel,
                comparisonMode(intentType),
                preferredChart(intentType),
                steps,
                DomainAnalyticalDefaults.generic(),
                binding,
                summary);
    }

    /** Decompose using resolved question semantics from {@link com.example.BACKEND.catalogue.decision.clarification.AnalyticalQuestionResolver}. */
    public AnalyticalReasoningPlan decomposeFromResolution(
            IntentResolution intent, ResolvedAnalyticalQuestion resolved
    ) {
        if (resolved == null || resolved.assumption() == null) {
            return decompose(intent, AnalyticalIntentType.GENERAL_ANALYSIS);
        }
        var a = resolved.assumption();
        SemanticAnalysisPlan semantic = resolved.semanticPlan();
        AnalyticalIntentType intentType = a.intent().canonical();
        DomainAnalyticalDefaults.DomainProfile domain = domainDefaults.resolve(intent.question(), null);

        MetricDecompositionBinding binding;
        String groupingLabel;
        List<DecompositionStep> steps;
        String summary;

        if (semantic != null && semantic.contributionPlan() != null) {
            ContributionAnalysisPlan cp = semantic.contributionPlan();
            binding = new MetricDecompositionBinding(
                    cp.numeratorMetric(), cp.numeratorLabel(),
                    com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType.RATIO,
                    cp.denominatorMetric(), cp.denominatorLabel(),
                    DenominatorContext.forRatio(cp.numeratorLabel(), cp.denominatorLabel()));
            groupingLabel = cp.denominatorLabel();
            steps = compositionRatioSteps(cp);
            summary = semantic.planSummary();
            intentType = AnalyticalIntentType.COMPOSITION;
        } else if (semantic != null && semantic.dimensionImpactPlan() != null) {
            DimensionImpactPlan dip = semantic.dimensionImpactPlan();
            binding = new MetricDecompositionBinding(
                    dip.metricColumn(), dip.metricLabel(),
                    com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType.SUM,
                    dip.bucketStrategy(), dip.dimensionLabel(),
                    DenominatorContext.forContribution(dip.metricLabel(), dip.dimensionLabel(), 0, 0, 0));
            groupingLabel = dip.dimensionLabel();
            steps = stepsFor(intentType, groupingLabel, binding.metricLabel(), domain);
            summary = semantic.planSummary();
            intentType = AnalyticalIntentType.CONTRIBUTION;
        } else {
            String grouping = a.grouping() != null && !a.grouping().isBlank()
                    ? a.grouping() : inferDimension(intent, domain, intentType);
            binding = new MetricDecompositionBinding(
                    a.primaryMetric(), a.primaryMetricLabel(), a.aggregation(),
                    grouping, grouping.replace('_', ' '),
                    DenominatorContext.forContribution(a.primaryMetricLabel(), grouping, 0, 0, 0));
            groupingLabel = binding.groupingLabel();
            steps = stepsFor(intentType, groupingLabel, binding.metricLabel(), domain);
            summary = buildSummary(intentType, binding.metricLabel(), groupingLabel,
                    comparisonMode(intentType), preferredChart(intentType), steps.size());
        }

        String comparison = comparisonMode(intentType);
        ChartSpec.ChartType chart = semantic != null && semantic.contributionPlan() != null
                ? ChartSpec.ChartType.DONUT
                : preferredChart(intentType);

        if (a.ambiguous() && a.ambiguityNote() != null && !a.ambiguityNote().isBlank()) {
            summary = a.ambiguityNote() + " " + summary;
        }

        return new AnalyticalReasoningPlan(
                intentType, binding.metricLabel(), groupingLabel,
                comparison, chart, steps, domain, binding, summary);
    }

    private List<DecompositionStep> compositionRatioSteps(ContributionAnalysisPlan cp) {
        return List.of(
                step(1, "COMPUTE_NUMERATOR_TOTAL",
                        "Sum " + cp.numeratorLabel() + " across dataset",
                        "numerator_total", true),
                step(2, "COMPUTE_DENOMINATOR_TOTAL",
                        "Sum " + cp.denominatorLabel() + " across dataset",
                        "denominator_total", true),
                step(3, "COMPUTE_SHARE_PCT",
                        "Compute " + cp.numeratorLabel() + " share of " + cp.denominatorLabel(),
                        "composition_share_pct", true),
                step(4, "IDENTIFY_DOMINANT_COMPONENT",
                        "Identify dominant revenue component in composition",
                        "dominant_component", true)
        );
    }

    /** Convert decomposition steps into investigation steps for the existing pipeline. */
    public List<InvestigationStep> toInvestigationSteps(AnalyticalReasoningPlan plan) {
        List<InvestigationStep> steps = new ArrayList<>();
        for (DecompositionStep ds : plan.decompositionSteps()) {
            steps.add(new InvestigationStep(
                    ds.order(),
                    ds.stepKey(),
                    ds.purpose(),
                    List.of(ds.outputMetric()),
                    strategyFor(ds.stepKey()),
                    ds.required()
            ));
        }
        return steps.isEmpty() ? fallbackSteps(plan.intent()) : steps;
    }

    private List<DecompositionStep> stepsFor(
            AnalyticalIntentType intent, String dimension, String metric,
            DomainAnalyticalDefaults.DomainProfile domain) {

        return switch (intent) {
            case CONTRIBUTION -> List.of(
                    step(1, "BUCKET_DIMENSION", "Bucket " + dimension + " into analytically meaningful ranges",
                            "bucketed_" + dimension, true),
                    step(2, "AGGREGATE_METRIC_BY_BUCKET", "Aggregate " + metric + " by each bucket",
                            "segment_total_value", true),
                    step(3, "COMPUTE_SHARE_PCT", "Express each bucket as share % of total " + metric,
                            "segment_share", true),
                    step(4, "RANK_DOMINANT_BUCKETS", "Rank buckets from highest to lowest contribution",
                            "ranked_segments", true),
                    step(5, "DETECT_CONCENTRATION", "Identify concentration — top buckets vs long tail",
                            "concentration_ratio", true),
                    step(6, "COMPUTE_LONG_TAIL_EFFECT", "Quantify revenue/volume in non-dominant buckets",
                            "long_tail_share", false)
            );

            case TREND_ANALYSIS -> List.of(
                    step(1, "EXTRACT_TIME_SERIES", "Retrieve " + metric + " across time periods",
                            "time_series_values", true),
                    step(2, "COMPUTE_PERIOD_DELTAS", "Calculate change between consecutive periods",
                            "period_over_period_delta", true),
                    step(3, "COMPUTE_TREND_SLOPE", "Fit trend slope — rising, falling, or flat trajectory",
                            "trend_slope", true),
                    step(4, "DETECT_ACCELERATION", "Detect acceleration or deceleration in growth rate",
                            "acceleration", false),
                    step(5, "COMPARE_CURRENT_VS_PRIOR", "Compare latest period vs prior and baseline",
                            "current_vs_previous", true)
            );

            case COMPARISON -> List.of(
                    step(1, "ESTABLISH_CURRENT_VALUES", "Compute " + metric + " for current entity/period",
                            "current_period_value", true),
                    step(2, "ESTABLISH_REFERENCE_VALUES", "Compute reference period or comparison entity",
                            "reference_period_value", true),
                    step(3, "COMPUTE_ABSOLUTE_DELTA", "Calculate absolute gap between current and reference",
                            "delta_absolute", true),
                    step(4, "COMPUTE_RELATIVE_DELTA", "Express gap as percentage change",
                            "delta_percentage", true),
                    step(5, "CONTEXTUALISE_VS_EXPECTED", "Compare observed vs expected based on historical norm",
                            "expected_vs_observed", false)
            );

            case ANOMALY_DETECTION -> List.of(
                    step(1, "COMPUTE_CURRENT_VALUES", "Measure current " + metric + " values",
                            "current_period_value", true),
                    step(2, "ESTABLISH_BASELINE", "Compute expected baseline from historical average",
                            "historical_baseline", true),
                    step(3, "COMPUTE_Z_SCORE", "Calculate deviation as z-score from baseline",
                            "deviation_magnitude", true),
                    step(4, "DETECT_OUTLIERS", "Flag segments exceeding normal variance",
                            "outlier_segments", true),
                    step(5, "IDENTIFY_AFFECTED_SEGMENTS", "Determine which groups drive the anomaly",
                            "affected_segments", true)
            );

            case RANKING -> List.of(
                    step(1, "COMPUTE_PRIMARY_AGGREGATE", "Compute " + metric + " for all entities in " + dimension,
                            "total_value", true),
                    step(2, "RANK_ENTITIES", "Order entities highest to lowest",
                            "ranked_entities", true),
                    step(3, "COMPUTE_PERCENTILE_POSITION", "Position leader and tail in distribution",
                            "percentile_rank", true),
                    step(4, "COMPARE_HIGHEST_VS_LOWEST", "Quantify gap between top and bottom performers",
                            "leader_to_tail_gap", true),
                    step(5, "DETECT_CONCENTRATION", "Flag dependency if top entity exceeds 25% share",
                            "concentration_share", false)
            );

            case CORRELATION -> List.of(
                    step(1, "PAIR_METRIC_DIMENSIONS", "Pair " + metric + " with candidate driver dimensions",
                            "metric_pairs", true),
                    step(2, "COMPUTE_COVARIATION", "Measure co-movement strength between variables",
                            "covariation_signal", true),
                    step(3, "RANK_RELATIONSHIP_STRENGTH", "Rank relationships by correlation magnitude",
                            "relationship_rank", true),
                    step(4, "IDENTIFY_NONLINEAR_PATTERNS", "Detect diminishing returns or threshold effects",
                            "nonlinear_pattern", false)
            );

            case DISTRIBUTION, SEGMENTATION -> List.of(
                    step(1, "BUCKET_DIMENSION", "Segment " + dimension + " into distribution buckets",
                            "distribution_buckets", true),
                    step(2, "AGGREGATE_BY_BUCKET", "Aggregate " + metric + " per bucket",
                            "bucket_values", true),
                    step(3, "COMPUTE_DISTRIBUTION_SHAPE", "Characterise skew, variance, and spread",
                            "distribution_profile", true),
                    step(4, "DETECT_OUTLIERS", "Identify buckets outside normal range",
                            "distribution_outliers", false),
                    step(5, "COMPARE_DOMINANT_VS_WEAK", "Contrast highest vs lowest buckets",
                            "dominant_vs_weak", true)
            );

            case RETENTION -> List.of(
                    step(1, "DEFINE_COHORT", "Define cohort entry period for repeat behaviour",
                            "cohort_definition", true),
                    step(2, "TRACK_REPEAT_RATE", "Measure repeat engagement over subsequent periods",
                            "retention_rate", true),
                    step(3, "COMPARE_COHORTS", "Compare retention curves across cohorts",
                            "cohort_comparison", true)
            );

            case EFFICIENCY -> List.of(
                    step(1, "COMPUTE_NUMERATOR", "Aggregate " + metric + " (numerator)",
                            "total_value", true),
                    step(2, "COMPUTE_DENOMINATOR", "Aggregate activity units (denominator)",
                            "volume", true),
                    step(3, "COMPUTE_EFFICIENCY_RATIO",
                            "Derive " + domain.efficiencyFormula().replace('_', ' ') + " per unit",
                            "efficiency_ratio", true),
                    step(4, "RANK_BY_EFFICIENCY", "Rank segments by yield, not just scale",
                            "efficiency_rank", true),
                    step(5, "COMPARE_VS_AVERAGE", "Contrast top vs average efficiency",
                            "efficiency_vs_average", true)
            );

            case COMPOSITION -> List.of(
                    step(1, "COMPUTE_SEGMENT_TOTALS", "Compute absolute value per segment",
                            "segment_total_value", true),
                    step(2, "COMPUTE_MIX_SHARE", "Express each segment as % of portfolio total",
                            "segment_share", true),
                    step(3, "IDENTIFY_DOMINANT_MIX", "Surface segments that define the portfolio mix",
                            "dominant_mix", true),
                    step(4, "DETECT_MIX_SHIFT", "Compare current mix vs equal-weight baseline",
                            "mix_vs_baseline", false)
            );

            case ROOT_CAUSE_INVESTIGATION -> List.of(
                    step(1, "QUANTIFY_TOTAL_CHANGE", "Measure total change in " + metric,
                            "primary_metric_change", true),
                    step(2, "DECOMPOSE_BY_SEGMENT", "Attribute change to each " + dimension + " segment",
                            "segment_contribution_to_change", true),
                    step(3, "SEPARATE_VOLUME_RATE", "Separate volume effect from rate effect",
                            "volume_rate_decomposition", true),
                    step(4, "IDENTIFY_DOMINANT_DRIVER", "Surface segment explaining majority of delta",
                            "dominant_driver_segment", true)
            );

            case STRATEGIC_PRIORITIZATION -> List.of(
                    step(1, "COMPUTE_VALUE_DIMENSION", "Measure scale — absolute " + metric,
                            "total_value", true),
                    step(2, "COMPUTE_EFFICIENCY_DIMENSION", "Measure yield per unit",
                            "efficiency_ratio", true),
                    step(3, "COMPUTE_MOMENTUM", "Measure growth trajectory",
                            "growth_rate", true),
                    step(4, "BUILD_COMPOSITE_SCORE", "Weight dimensions into strategic priority score",
                            "composite_score", true)
            );

            case FORECASTING -> List.of(
                    step(1, "EXTRACT_RECENT_TREND", "Compute observed growth from recent periods",
                            "recent_trend_values", true),
                    step(2, "COMPUTE_VOLATILITY", "Measure variance for confidence band",
                            "volatility", true),
                    step(3, "PROJECT_FORWARD", "Project next period from trend slope",
                            "projection", true)
            );

            default -> List.of(
                    step(1, "COMPUTE_PRIMARY_METRICS", "Compute primary " + metric + " aggregates",
                            "total_value", true),
                    step(2, "COMPARE_VS_AVERAGE", "Position results against peer average",
                            "peer_comparison", false)
            );
        };
    }

    private ComparativeStrategy strategyFor(String stepKey) {
        return switch (stepKey) {
            case "COMPUTE_PERIOD_DELTAS", "COMPUTE_ABSOLUTE_DELTA", "COMPUTE_RELATIVE_DELTA",
                 "COMPARE_CURRENT_VS_PRIOR", "COMPARE_VS_AVERAGE", "COMPARE_HIGHEST_VS_LOWEST",
                 "COMPARE_DOMINANT_VS_WEAK", "COMPARE_COHORTS" ->
                    ComparativeStrategy.PERIOD_OVER_PERIOD;
            case "COMPUTE_PERCENTILE_POSITION", "RANK_ENTITIES", "RANK_DOMINANT_BUCKETS",
                 "RANK_BY_EFFICIENCY", "RANK_RELATIONSHIP_STRENGTH" ->
                    ComparativeStrategy.PERCENTILE_RANK;
            case "DETECT_CONCENTRATION", "COMPUTE_LONG_TAIL_EFFECT", "DETECT_MIX_SHIFT" ->
                    ComparativeStrategy.CONCENTRATION_RATIO;
            case "COMPUTE_Z_SCORE", "DETECT_OUTLIERS", "IDENTIFY_AFFECTED_SEGMENTS" ->
                    ComparativeStrategy.Z_SCORE_DEVIATION;
            case "ESTABLISH_BASELINE", "CONTEXTUALISE_VS_EXPECTED" ->
                    ComparativeStrategy.VS_HISTORICAL_BASELINE;
            case "BUILD_COMPOSITE_SCORE" ->
                    ComparativeStrategy.WEIGHTED_COMPOSITE_SCORE;
            default -> ComparativeStrategy.VS_PEER_AVERAGE;
        };
    }

    private List<InvestigationStep> fallbackSteps(AnalyticalIntentType intent) {
        return List.of(new InvestigationStep(1, "COMPUTE_PRIMARY_METRICS",
                "Compute primary aggregates for " + intent.name(),
                List.of("total_value"), ComparativeStrategy.VS_PEER_AVERAGE, true));
    }

    private String inferMetric(IntentResolution intent, DomainAnalyticalDefaults.DomainProfile domain) {
        String q = intent.question().toLowerCase(Locale.ROOT);
        if (q.contains("distance") && q.contains("contribute")) return domain.businessRevenueLabel();
        if (q.contains("revenue") || q.contains("fare") || q.contains("amount")) {
            return domain.businessRevenueLabel();
        }
        if (q.contains("efficien") || q.contains("per mile") || q.contains("yield")) {
            return "Efficiency";
        }
        if (q.contains("volume") || q.contains("count") || q.contains("trips")) return "Trip volume";
        return domain.businessRevenueLabel();
    }

    private String inferDimension(IntentResolution intent, DomainAnalyticalDefaults.DomainProfile domain,
                                  AnalyticalIntentType intentType) {
        String q = intent.question().toLowerCase(Locale.ROOT);
        if (q.contains("trip distance") || q.contains("distance")) return "Trip distance";
        if (q.contains("hour") || q.contains("time of day")) return "Hour of day";
        if (q.contains("zone") || q.contains("borough") || q.contains("location")) return "Pickup zone";
        if (q.contains("payment") || q.contains("card")) return "Payment type";
        if (intentType == AnalyticalIntentType.TREND_ANALYSIS) return "Time period";
        return domain.distanceColumn() + " bucket";
    }

    private String comparisonMode(AnalyticalIntentType intent) {
        return switch (intent) {
            case CONTRIBUTION, COMPOSITION -> "dominant_vs_long_tail";
            case COMPARISON -> "current_vs_previous";
            case ANOMALY_DETECTION -> "expected_vs_observed";
            case RANKING, EFFICIENCY -> "highest_vs_lowest";
            case DISTRIBUTION, SEGMENTATION -> "dominant_vs_weak";
            case TREND_ANALYSIS -> "current_vs_previous";
            case CORRELATION -> "covariation_strength";
            default -> "vs_peer_average";
        };
    }

    private ChartSpec.ChartType preferredChart(AnalyticalIntentType intent) {
        return switch (intent) {
            case CONTRIBUTION, COMPOSITION -> ChartSpec.ChartType.DONUT;
            case TREND_ANALYSIS, FORECASTING -> ChartSpec.ChartType.LINE;
            case RANKING, EFFICIENCY -> ChartSpec.ChartType.HBAR;
            case DISTRIBUTION, SEGMENTATION -> ChartSpec.ChartType.HISTOGRAM;
            case COMPARISON -> ChartSpec.ChartType.GROUPED_BAR;
            case ANOMALY_DETECTION, CORRELATION -> ChartSpec.ChartType.BAR;
            default -> ChartSpec.ChartType.BAR;
        };
    }

    private String buildSummary(AnalyticalIntentType intent, String metric, String dimension,
                                 String comparison, ChartSpec.ChartType chart, int stepCount) {
        return String.format(
                "%s analysis: measure %s grouped by %s, compare via %s, support with %s chart (%d steps).",
                intent.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                metric, dimension, comparison, chart.name(), stepCount);
    }

    private static DecompositionStep step(int order, String key, String purpose,
                                            String output, boolean required) {
        return new DecompositionStep(order, key, purpose, output, required);
    }
}
