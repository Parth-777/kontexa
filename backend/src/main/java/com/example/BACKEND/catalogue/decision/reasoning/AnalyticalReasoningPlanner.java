package com.example.BACKEND.catalogue.decision.reasoning;

import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalIntentKind;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.presentation.ResponseMode;
import com.example.BACKEND.catalogue.decision.presentation.VisualizationStrategyEngine;
import com.example.BACKEND.catalogue.decision.semantics.AnalyticalRelationship;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.catalogue.decision.transforms.DerivedDimensionRegistry;
import com.example.BACKEND.catalogue.decision.transforms.SemanticConcept;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Core orchestration engine — builds question-specific reasoning, query, and viz plans.
 */
@Component
public class AnalyticalReasoningPlanner {

    private final VisualizationStrategyEngine visualizationStrategy;
    private final DerivedDimensionRegistry  dimensionRegistry;

    public AnalyticalReasoningPlanner(
            VisualizationStrategyEngine visualizationStrategy,
            DerivedDimensionRegistry dimensionRegistry
    ) {
        this.visualizationStrategy = visualizationStrategy;
        this.dimensionRegistry = dimensionRegistry;
    }

    public QuestionDrivenReasoningPlan plan(
            QuestionSemantics semantics,
            MetricResolution resolution
    ) {
        AnalyticalIntentType intent = semantics.intent();
        List<QuestionDrivenReasoningPlan.ReasoningStep> steps = buildReasoningSteps(semantics, resolution);
        List<QuestionDrivenReasoningPlan.QueryPlanStep> queries = buildQueryPlan(semantics, resolution);
        QuestionDrivenReasoningPlan.VisualizationStrategy viz =
                visualizationStrategy.plan(semantics, resolution);

        return new QuestionDrivenReasoningPlan(
                semantics.question(), intent, steps, queries, viz, semantics, resolution, List.of());
    }

    private List<QuestionDrivenReasoningPlan.ReasoningStep> buildReasoningSteps(
            QuestionSemantics s, MetricResolution r
    ) {
        List<QuestionDrivenReasoningPlan.ReasoningStep> steps = new ArrayList<>();

        steps.add(step("resolve_metrics",
                "Resolving " + label(r.primaryMetricLabel(), "metrics"),
                "Binding primary metric: " + r.primaryMetric()));

        if (r.relationshipVariable() != null) {
            steps.add(step("resolve_relationship",
                    "Resolving relationship variables",
                    r.relationshipVariable() + " vs " + r.primaryMetric()));
        } else if (r.targetMetric() != null) {
            steps.add(step("identify_contribution",
                    "Identifying " + label(r.primaryMetricLabel(), "contribution"),
                    "Numerator " + r.primaryMetric() + " vs " + r.targetMetric()));
            if ("tip_amount".equals(r.primaryMetric())) {
                steps.add(step("compare_components",
                        "Comparing tip revenue vs fare revenue",
                        "Component breakdown of revenue sources"));
            }
        }

        if (r.dimension() != null) {
            steps.add(step("resolve_dimension",
                    "Grouping by " + label(r.dimensionLabel(), r.dimension()),
                    "Dimension column: " + r.dimension()));
            dimensionRegistry.resolveConcept(r.dimension(), s.question()).ifPresent(concept -> {
                if (dimensionRegistry.isTemporal(concept)) {
                    steps.add(step("resolve_timestamp",
                            "Resolving timestamp column",
                            "Auto-detect pickup_datetime or schema timestamp for derivation"));
                    steps.add(step("derive_temporal",
                            transformationTitle(concept),
                            "Warehouse-derived grouping expression"));
                } else if (dimensionRegistry.isNumericBucket(concept)) {
                    steps.add(step("apply_bucketization",
                            "Applying numeric bucketization",
                            "Dynamic CASE ranges for " + r.dimension()));
                }
            });
        }

        steps.add(switch (s.relationship()) {
            case SHARE_OF_TOTAL -> step("compute_share",
                    "Calculating share-of-total percentages",
                    "Ratio of " + r.primaryMetric() + " to " + r.targetMetric());
            case DIMENSION_BREAKDOWN -> step("compute_segments",
                    "Computing segment contributions",
                    "Share % across " + label(r.dimensionLabel(), "segments"));
            case TREND_OVER_TIME -> step("aggregate_time",
                    "Aggregating over time",
                    "Temporal grain from question");
            case RANKING -> step("rank_entities",
                    "Ranking and ordering segments",
                    "Descending order by " + r.primaryMetric());
            case COMPARISON -> step("compare_segments",
                    "Comparing segment values",
                    "Side-by-side comparison");
            case DISTRIBUTION -> step("bucketize",
                    "Bucketizing and computing frequencies",
                    "Distribution shape across " + label(r.dimensionLabel(), "ranges"));
            case EFFICIENCY -> step("compute_efficiency",
                    "Computing yield per unit",
                    "Efficiency ratios");
            case METRIC_RELATIONSHIP -> step("compute_correlation",
                    "Computing variable relationship",
                    "CORR(" + r.relationshipVariable() + ", " + r.primaryMetric() + ")");
            case EXACT_LOOKUP -> step("lookup_scalar",
                    "Looking up exact metric value",
                    "Single-value retrieval");
        });

        steps.add(step("validate_results",
                "Validating aggregation consistency",
                "Reconcile totals and entity alignment"));

        steps.add(step("generate_visualization",
                "Generating " + vizLabel(s),
                "Chart/table strategy for " + s.intent().name()));

        steps.add(step("synthesize_insight",
                "Synthesizing findings",
                "Evidence-backed narrative"));

        return steps;
    }

    private List<QuestionDrivenReasoningPlan.QueryPlanStep> buildQueryPlan(
            QuestionSemantics s, MetricResolution r
    ) {
        List<QuestionDrivenReasoningPlan.QueryPlanStep> plans = new ArrayList<>();
        String metric = r.primaryMetric();
        String dim = r.dimension();
        String grouping = r.grouping();

        switch (s.relationship()) {
            case SHARE_OF_TOTAL -> {
                plans.add(query("composition", metric, null, "composition",
                        AnalyticalIntentKind.CONTRIBUTION,
                        "SUM(" + metric + ") / SUM(" + r.targetMetric() + ")"));
                if (r.targetMetric() != null) {
                    plans.add(query("compare_components", r.targetMetric(), null, "composition",
                            AnalyticalIntentKind.COMPARISON,
                            "Compare " + metric + " vs fare and other revenue components"));
                }
            }
            case TREND_OVER_TIME -> plans.add(query("trend", metric,
                    dim != null ? dim : "pickup_hour", grouping != null ? grouping : "pickup_hour",
                    AnalyticalIntentKind.TREND, "Time-series aggregation"));
            case RANKING -> plans.add(query("ranking", metric, dim, grouping,
                    AnalyticalIntentKind.RANKING, "Top-N ranking"));
            case COMPARISON -> plans.add(query("comparison", metric, dim, grouping,
                    AnalyticalIntentKind.COMPARISON, "Segment comparison"));
            case DIMENSION_BREAKDOWN -> plans.add(query("contribution", metric, dim, grouping,
                    AnalyticalIntentKind.CONTRIBUTION, "Segment contribution SUM(" + metric + ")"));
            case DISTRIBUTION -> plans.add(query("distribution", metric, dim, grouping,
                    AnalyticalIntentKind.DISTRIBUTION, "Row count distribution"));
            case EFFICIENCY -> plans.add(query("efficiency", metric, dim, grouping,
                    AnalyticalIntentKind.EFFICIENCY, "Efficiency AVG(" + metric + ")"));
            case METRIC_RELATIONSHIP -> {
                String source = r.relationshipVariable() != null ? r.relationshipVariable() : r.targetMetric();
                plans.add(query("relationship", metric, source, "relationship",
                        AnalyticalIntentKind.RELATIONSHIP,
                        "CORR(" + source + ", " + metric + ")"));
            }
            case EXACT_LOOKUP -> plans.add(query("scalar", metric, null, "",
                    AnalyticalIntentKind.CONTRIBUTION, "Scalar lookup"));
        }

        if (plans.isEmpty()) {
            plans.add(query("primary", metric, dim, grouping,
                    mapSqlIntent(s.intent()),
                    "Primary analytical query"));
        }
        return plans;
    }

    private AnalyticalIntentKind mapSqlIntent(AnalyticalIntentType intent) {
        return switch (intent) {
            case TREND_ANALYSIS, FORECASTING -> AnalyticalIntentKind.TREND;
            case RANKING -> AnalyticalIntentKind.RANKING;
            case COMPARISON -> AnalyticalIntentKind.COMPARISON;
            case DISTRIBUTION, SEGMENTATION -> AnalyticalIntentKind.DISTRIBUTION;
            case RELATIONSHIP -> AnalyticalIntentKind.RELATIONSHIP;
            case COMPOSITION, CONTRIBUTION -> AnalyticalIntentKind.CONTRIBUTION;
            case EFFICIENCY -> AnalyticalIntentKind.EFFICIENCY;
            default -> AnalyticalIntentKind.CONTRIBUTION;
        };
    }

    private String vizLabel(QuestionSemantics s) {
        return switch (s.intent()) {
            case CONTRIBUTION, COMPOSITION -> "contribution visualization";
            case TREND_ANALYSIS -> "trend chart";
            case RANKING -> "ranked table";
            case DISTRIBUTION -> "distribution chart";
            case RELATIONSHIP -> "relationship scatter";
            default -> "analytical output";
        };
    }

    private String transformationTitle(SemanticConcept concept) {
        return switch (concept) {
            case WEEKEND_DAY -> "Deriving weekend vs weekday";
            case HOUR_OF_DAY -> "Deriving hour of day";
            case MONTH -> "Deriving monthly bucket";
            case TRIP_DISTANCE_BUCKET -> "Deriving distance buckets";
            default -> "Deriving " + concept.name().toLowerCase().replace('_', ' ');
        };
    }

    private String label(String label, String fallback) {
        return label != null && !label.isBlank() ? label : fallback;
    }

    private QuestionDrivenReasoningPlan.ReasoningStep step(String key, String title, String desc) {
        return new QuestionDrivenReasoningPlan.ReasoningStep(key, title, desc);
    }

    private QuestionDrivenReasoningPlan.QueryPlanStep query(
            String key, String metric, String dim, String grouping,
            AnalyticalIntentKind sqlIntent, String desc
    ) {
        return new QuestionDrivenReasoningPlan.QueryPlanStep(
                key, metric, dim, grouping != null ? grouping : "", sqlIntent, desc);
    }
}
