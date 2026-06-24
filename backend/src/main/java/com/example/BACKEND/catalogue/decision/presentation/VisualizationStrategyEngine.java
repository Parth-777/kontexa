package com.example.BACKEND.catalogue.decision.presentation;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.reasoning.QuestionDrivenReasoningPlan.VisualizationStrategy;
import com.example.BACKEND.catalogue.decision.semantics.AnalyticalRelationship;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import org.springframework.stereotype.Component;

/**
 * Selects chart type and response mode from question semantics — not a single default chart.
 */
@Component
public class VisualizationStrategyEngine {

    public VisualizationStrategy plan(QuestionSemantics semantics, MetricResolution resolution) {
        if (semantics.relationship() == AnalyticalRelationship.SHARE_OF_TOTAL) {
            return new VisualizationStrategy(
                    ChartSpec.ChartType.DONUT, ResponseMode.MIXED, true,
                    "Contribution share — donut + table");
        }

        return switch (semantics.intent()) {
            case CONTRIBUTION, COMPOSITION -> new VisualizationStrategy(
                    ChartSpec.ChartType.DONUT, ResponseMode.MIXED, true,
                    "Segment contribution — stacked view + share table");
            case TREND_ANALYSIS, FORECASTING -> new VisualizationStrategy(
                    ChartSpec.ChartType.LINE, ResponseMode.CHART, false,
                    "Temporal trend — line chart");
            case RANKING -> new VisualizationStrategy(
                    ChartSpec.ChartType.BAR, ResponseMode.TABLE, true,
                    "Ranking — table first, horizontal bars");
            case COMPARISON -> new VisualizationStrategy(
                    ChartSpec.ChartType.BAR, ResponseMode.MIXED, false,
                    "Segment comparison — grouped bars");
            case DISTRIBUTION, SEGMENTATION -> new VisualizationStrategy(
                    ChartSpec.ChartType.BAR, ResponseMode.MIXED, resolution.dimension() != null,
                    "Distribution — histogram-style bars");
            case EFFICIENCY -> new VisualizationStrategy(
                    ChartSpec.ChartType.BAR, ResponseMode.TABLE, true,
                    "Efficiency ranking — table first");
            case RELATIONSHIP -> new VisualizationStrategy(
                    ChartSpec.ChartType.LINE, ResponseMode.MIXED, false,
                    "Metric relationship — correlation summary");
            default -> new VisualizationStrategy(
                    ChartSpec.ChartType.BAR, ResponseMode.MIXED, true,
                    "General analysis");
        };
    }
}
