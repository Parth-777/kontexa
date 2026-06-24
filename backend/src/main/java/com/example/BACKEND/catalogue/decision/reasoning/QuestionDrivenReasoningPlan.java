package com.example.BACKEND.catalogue.decision.reasoning;

import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalIntentKind;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.presentation.ResponseMode;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.catalogue.charts.ChartSpec;

import java.util.List;

/**
 * Dynamic per-question reasoning plan — drives traces, SQL, and visualization.
 */
public record QuestionDrivenReasoningPlan(
        String                    question,
        AnalyticalIntentType      intent,
        List<ReasoningStep>       reasoningSteps,
        List<QueryPlanStep>       queryPlan,
        VisualizationStrategy     visualizationStrategy,
        QuestionSemantics         semantics,
        MetricResolution          resolution,
        java.util.List<com.example.BACKEND.catalogue.decision.transforms.TransformationStep> transformationSteps
) {
    public QuestionDrivenReasoningPlan withTransformationSteps(
            java.util.List<com.example.BACKEND.catalogue.decision.transforms.TransformationStep> steps
    ) {
        return new QuestionDrivenReasoningPlan(
                question, intent, reasoningSteps, queryPlan, visualizationStrategy,
                semantics, resolution, steps);
    }
    public record ReasoningStep(
            String stepKey,
            String title,
            String description
    ) {}

    public record QueryPlanStep(
            String               key,
            String               metric,
            String               dimension,
            String               grouping,
            AnalyticalIntentKind sqlIntent,
            String               description
    ) {}

    public record VisualizationStrategy(
            ChartSpec.ChartType chartType,
            ResponseMode        responseMode,
            boolean             tableFirst,
            String              rationale
    ) {}
}
