package com.example.BACKEND.catalogue.decision.clarification;

import com.example.BACKEND.catalogue.decision.exploration.AnalyticalExecutionMode;
import com.example.BACKEND.catalogue.decision.exploration.InterpretationCandidatePlan;
import com.example.BACKEND.catalogue.decision.exploration.PlannerConfidenceTier;
import com.example.BACKEND.catalogue.decision.semantic.SemanticAnalysisPlan;

import java.util.List;

/**
 * Fully resolved (or safely assumed) analytical question with viability metadata.
 */
public record ResolvedAnalyticalQuestion(
        AnalyticalAssumption       assumption,
        List<ClarificationOption>  alternatives,
        boolean                    viable,
        List<String>               viabilityIssues,
        List<String>               availableMetrics,
        String                     suggestedReformulation,
        SemanticAnalysisPlan       semanticPlan,
        List<InterpretationCandidatePlan> candidateInterpretations,
        boolean                    exploratoryMode,
        double                     confidencePenalty,
        String                     explorationNote,
        PlannerConfidenceTier      confidenceTier,
        AnalyticalExecutionMode    executionMode
) {
    public ResolvedAnalyticalQuestion(
            AnalyticalAssumption assumption,
            List<ClarificationOption> alternatives,
            boolean viable,
            List<String> viabilityIssues,
            List<String> availableMetrics,
            String suggestedReformulation,
            SemanticAnalysisPlan semanticPlan
    ) {
        this(assumption, alternatives, viable, viabilityIssues, availableMetrics,
                suggestedReformulation, semanticPlan, List.of(), false, 0, "",
                PlannerConfidenceTier.MEDIUM, AnalyticalExecutionMode.HYBRID);
    }

    public ResolvedAnalyticalQuestion(
            AnalyticalAssumption assumption,
            List<ClarificationOption> alternatives,
            boolean viable,
            List<String> viabilityIssues,
            List<String> availableMetrics,
            String suggestedReformulation
    ) {
        this(assumption, alternatives, viable, viabilityIssues, availableMetrics,
                suggestedReformulation, null, List.of(), false, 0, "",
                PlannerConfidenceTier.MEDIUM, AnalyticalExecutionMode.HYBRID);
    }

    /**
     * Apply post-execution candidate selection — updates assumption after evidence scoring.
     */
    public ResolvedAnalyticalQuestion withWinningCandidate(
            InterpretationCandidatePlan winner,
            String selectionNote,
            double ambiguityPenalty
    ) {
        if (winner == null || assumption == null) return this;

        AnalyticalAssumption updated = new AnalyticalAssumption(
                assumption.interpretedQuestion(),
                winner.primaryMetric(),
                winner.primaryMetricLabel(),
                winner.secondaryMetric(),
                winner.grouping() != null ? winner.grouping() : "",
                winner.aggregation(),
                winner.intent().canonical(),
                assumption.assumptions(),
                Math.max(0.35, assumption.resolutionConfidence() - ambiguityPenalty),
                assumption.ambiguous(),
                selectionNote != null && !selectionNote.isBlank() ? selectionNote : explorationNote
        );

        String note = selectionNote != null && !selectionNote.isBlank() ? selectionNote : explorationNote;
        return new ResolvedAnalyticalQuestion(
                updated, alternatives, viable, viabilityIssues, availableMetrics,
                suggestedReformulation, semanticPlan, candidateInterpretations,
                true, ambiguityPenalty, note, confidenceTier, executionMode);
    }

    public static ResolvedAnalyticalQuestion notViable(
            AnalyticalAssumption assumption,
            List<String> issues,
            List<String> availableMetrics,
            String reformulation
    ) {
        return new ResolvedAnalyticalQuestion(
                assumption, List.of(), false, issues, availableMetrics, reformulation,
                null, List.of(), true, 0.3, "",
                PlannerConfidenceTier.LOW, AnalyticalExecutionMode.EXPLORATORY_HEURISTIC);
    }

    public static ResolvedAnalyticalQuestion notViable(
            AnalyticalAssumption assumption,
            List<String> issues,
            List<String> availableMetrics,
            String reformulation,
            SemanticAnalysisPlan semanticPlan
    ) {
        return new ResolvedAnalyticalQuestion(
                assumption, List.of(), false, issues, availableMetrics, reformulation,
                semanticPlan, List.of(), true, 0.3, "",
                PlannerConfidenceTier.LOW, AnalyticalExecutionMode.EXPLORATORY_HEURISTIC);
    }
}
