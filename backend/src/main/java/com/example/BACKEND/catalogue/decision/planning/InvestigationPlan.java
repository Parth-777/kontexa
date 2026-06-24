package com.example.BACKEND.catalogue.decision.planning;

import java.util.List;

/**
 * The structured analytical investigation plan produced by {@link AnalyticalPlanningEngine}.
 *
 * This plan is the system's internal reasoning framework — it defines what the
 * runtime intends to measure, compare, and synthesise BEFORE any SQL is executed.
 *
 * The plan is passed to:
 *   - {@link com.example.BACKEND.catalogue.decision.evidence.EvidenceCoverageChecker}
 *     (to validate completeness)
 *   - {@link com.example.BACKEND.catalogue.decision.synthesis.EvidenceToPromptTransformer}
 *     (to give the LLM the analytical context — not just raw evidence)
 *
 * Fields:
 *   planId              — unique identifier for this plan
 *   intentType          — classified analytical intent
 *   depth               — how deeply to investigate
 *   steps               — ordered investigation steps
 *   metricRequirements  — all metrics the plan depends on
 *   comparativeFramework— how to compare and frame results
 *   dimensionalFocus    — key analytical dimensions (entity, segment, time, etc.)
 *   planningRationale   — why this plan was selected for this question
 */
public record InvestigationPlan(
        String                    planId,
        AnalyticalIntentType      intentType,
        PlanDepth                 depth,
        List<InvestigationStep>   steps,
        List<MetricRequirement>   metricRequirements,
        ComparativeFramework      comparativeFramework,
        List<String>              dimensionalFocus,
        String                    planningRationale,
        AnalyticalReasoningPlan   reasoningPlan,
        com.example.BACKEND.catalogue.decision.clarification.ResolvedAnalyticalQuestion questionResolution,
        com.example.BACKEND.catalogue.decision.reasoning.QuestionDrivenReasoningPlan questionDrivenPlan
) {
    public InvestigationPlan(
            String planId, AnalyticalIntentType intentType, PlanDepth depth,
            List<InvestigationStep> steps, List<MetricRequirement> metricRequirements,
            ComparativeFramework comparativeFramework, List<String> dimensionalFocus,
            String planningRationale
    ) {
        this(planId, intentType, depth, steps, metricRequirements, comparativeFramework,
                dimensionalFocus, planningRationale, null, null, null);
    }

    public InvestigationPlan(
            String planId, AnalyticalIntentType intentType, PlanDepth depth,
            List<InvestigationStep> steps, List<MetricRequirement> metricRequirements,
            ComparativeFramework comparativeFramework, List<String> dimensionalFocus,
            String planningRationale, AnalyticalReasoningPlan reasoningPlan
    ) {
        this(planId, intentType, depth, steps, metricRequirements, comparativeFramework,
                dimensionalFocus, planningRationale, reasoningPlan, null, null);
    }

    public InvestigationPlan(
            String planId, AnalyticalIntentType intentType, PlanDepth depth,
            List<InvestigationStep> steps, List<MetricRequirement> metricRequirements,
            ComparativeFramework comparativeFramework, List<String> dimensionalFocus,
            String planningRationale, AnalyticalReasoningPlan reasoningPlan,
            com.example.BACKEND.catalogue.decision.clarification.ResolvedAnalyticalQuestion questionResolution
    ) {
        this(planId, intentType, depth, steps, metricRequirements, comparativeFramework,
                dimensionalFocus, planningRationale, reasoningPlan, questionResolution, null);
    }
}
