package com.example.BACKEND.catalogue.decision.presentation.executive;

import com.example.BACKEND.catalogue.charts.ChartSpec;

import java.util.List;

/**
 * User-facing analytical answer — no pipeline internals.
 */
public record ExecutiveInsightCard(
        String title,
        String executiveSummary,
        List<ExecutiveSupportingMetric> supportingMetrics,
        ChartSpec visualization,
        String keyTakeaway,
        ExecutiveConfidenceLabel confidenceLabel,
        String secondaryInterpretation
) {
    public static ExecutiveInsightCard empty(String message, ExecutiveConfidenceLabel confidence) {
        return new ExecutiveInsightCard(
                "Analysis",
                message,
                List.of(),
                null,
                message,
                confidence,
                ""
        );
    }
}
