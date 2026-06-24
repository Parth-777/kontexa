package com.example.BACKEND.catalogue.decision.clarification;

import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigation;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.reasoning.QuestionDrivenReasoningPlan;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;

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
) {}
