package com.example.BACKEND.catalogue.decision.planning;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.governance.MetricDecompositionBinding;

import java.util.List;

/**
 * Explicit multi-step reasoning plan produced before any warehouse query runs.
 *
 * Answers: what metric matters, what dimension groups it, what comparison explains it,
 * and what chart best supports the narrative.
 */
public record AnalyticalReasoningPlan(
        AnalyticalIntentType      intent,
        String                    primaryMetric,
        String                    groupingDimension,
        String                    comparisonMode,
        ChartSpec.ChartType       preferredChart,
        List<DecompositionStep>   decompositionSteps,
        DomainAnalyticalDefaults.DomainProfile domainProfile,
        MetricDecompositionBinding metricBinding,
        String                    planSummary
) {
    public static AnalyticalReasoningPlan empty(AnalyticalIntentType intent) {
        return new AnalyticalReasoningPlan(
                intent, "value", "", "vs_peer_average",
                ChartSpec.ChartType.BAR, List.of(), DomainAnalyticalDefaults.generic(), null, "");
    }
}
