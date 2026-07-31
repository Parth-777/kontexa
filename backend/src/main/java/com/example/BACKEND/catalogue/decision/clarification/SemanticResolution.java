package com.example.BACKEND.catalogue.decision.clarification;

import com.example.BACKEND.catalogue.decision.exploration.AnalyticalExecutionMode;
import com.example.BACKEND.catalogue.decision.exploration.PlannerConfidenceTier;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigation;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.reasoning.QuestionDrivenReasoningPlan;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;

import java.util.List;

/**
 * Full semantic pipeline output for a single question.
 */
public record SemanticResolution(
        ResolvedAnalyticalQuestion resolved,
        QuestionSemantics          semantics,
        MetricResolution           metricResolution,
        QuestionDrivenReasoningPlan reasoningPlan,
        QuestionInvestigation      investigation,
        AnalysisPlan               analysisPlan
) {
    /**
     * Inert placeholder used when {@code semantic.planner.mode=gpt}.
     * Does not invoke CatalogQuestionMatcher, SchemaDrivenMetricResolver,
     * MetricResolutionEngine, or any other legacy lexical planner.
     * GPT StructuredSemanticPlanner remains the sole metric/dimension authority.
     */
    public static SemanticResolution bypassedForGptPlanner(String question) {
        String q = question != null ? question : "";
        QuestionSemantics semantics = QuestionSemantics.unresolved(q);
        MetricResolution resolution = MetricResolution.rejected(
                "Bypassed: GPT mode does not run legacy lexical metric resolution");
        QuestionDrivenReasoningPlan reasoningPlan = new QuestionDrivenReasoningPlan(
                q,
                AnalyticalIntentType.GENERAL_ANALYSIS,
                List.of(),
                List.of(),
                null,
                semantics,
                resolution,
                List.of());
        AnalyticalAssumption assumption = new AnalyticalAssumption(
                q,
                "",
                "",
                null,
                "",
                AggregationType.SUM,
                AnalyticalIntentType.GENERAL_ANALYSIS,
                List.of("Lexical resolveFull bypassed — GPT StructuredSemanticPlanner is authority"),
                0.0,
                false,
                "gpt_mode_lexical_bypass");
        ResolvedAnalyticalQuestion resolved = new ResolvedAnalyticalQuestion(
                assumption,
                List.of(),
                true,
                List.of(),
                List.of(),
                "",
                null,
                List.of(),
                false,
                0,
                "Lexical stack bypassed (GPT mode)",
                PlannerConfidenceTier.HIGH,
                AnalyticalExecutionMode.STRICT_SEMANTIC);
        AnalysisPlan analysisPlan = AnalysisPlan.blocked(
                q, "Lexical AnalysisPlan unused in GPT mode");
        return new SemanticResolution(
                resolved, semantics, resolution, reasoningPlan, null, analysisPlan);
    }
}
