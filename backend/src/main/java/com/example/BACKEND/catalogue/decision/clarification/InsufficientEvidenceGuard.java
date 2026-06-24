package com.example.BACKEND.catalogue.decision.clarification;

import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.findings.StructuredFindingsBundle;
import com.example.BACKEND.catalogue.decision.reasoning.AnalyticalReasoningOrchestrator.ReasoningResult;
import org.springframework.stereotype.Component;

/**
 * Assesses whether execution produced presentable evidence.
 * Never hard-blocks the pipeline — callers use {@link #assess} for confidence only.
 */
@Component
public class InsufficientEvidenceGuard {

    private static final int MIN_FINDINGS = 1;

    public EvidenceAssessment assess(
            StructuredFindingsBundle bundle,
            ReasoningResult reasoning,
            ExecutionFindings executionFindings,
            AnalyticalDepthResult depthResult
    ) {
        int findings = bundle != null ? bundle.totalFindingCount() : 0;
        int prioritized = reasoning != null ? reasoning.prioritizedFindings().size() : 0;
        boolean hasFindings = findings >= MIN_FINDINGS || prioritized >= MIN_FINDINGS;

        MaterializedQueryResult mat = executionFindings != null
                ? executionFindings.materializedResult() : null;
        boolean hasMaterialized = mat != null && mat.hasContent();
        boolean hasGrouped = mat != null && mat.primaryGrouping() != null
                && mat.primaryGrouping().rankedEntries() != null
                && !mat.primaryGrouping().rankedEntries().isEmpty();

        boolean hasDepth = depthResult != null && depthResult.segmentBuckets() != null
                && !depthResult.segmentBuckets().isEmpty();

        boolean hasScalar = mat != null && mat.resultType() == com.example.BACKEND.catalogue.decision.execution.materialization.AnalyticalResultType.SCALAR_RESULT
                && mat.hasContent();

        boolean hasAnyData = hasFindings || hasMaterialized || hasGrouped || hasDepth || hasScalar;

        double penalty = 0;
        if (!hasFindings) penalty += 0.15;
        if (!hasGrouped && !hasDepth && !hasScalar) penalty += 0.10;

        boolean strongFindings = hasFindings || hasGrouped || hasScalar;

        String reason = explain(bundle, executionFindings, hasFindings, hasGrouped, hasDepth, hasScalar);
        return new EvidenceAssessment(hasAnyData, strongFindings, penalty, reason);
    }

    /** @deprecated Prefer {@link #assess}; retained for tests. */
    public boolean hasSufficientEvidence(
            StructuredFindingsBundle bundle,
            ReasoningResult reasoning,
            ExecutionFindings executionFindings
    ) {
        return assess(bundle, reasoning, executionFindings, null).presentable();
    }

    public String explain(
            StructuredFindingsBundle bundle,
            ExecutionFindings executionFindings
    ) {
        return explain(bundle, executionFindings, false, false, false, false);
    }

    private String explain(
            StructuredFindingsBundle bundle,
            ExecutionFindings executionFindings,
            boolean hasFindings,
            boolean hasGrouped,
            boolean hasDepth,
            boolean hasScalar
    ) {
        int rows = executionFindings != null && executionFindings.materializedResult() != null
                ? executionFindings.materializedResult().totalRows() : 0;
        int findings = bundle != null ? bundle.totalFindingCount() : 0;

        if (rows == 0 && !hasGrouped && !hasDepth && !hasScalar) {
            return "No rows returned from the dataset for this grouping.";
        }
        if (!hasFindings && hasScalar) {
            return "Scalar contribution aggregate available from warehouse result.";
        }
        if (!hasFindings && hasGrouped) {
            return "Grouped aggregates available; formal findings did not pass governance thresholds.";
        }
        if (!hasFindings && hasDepth) {
            return "Structural segment patterns detected; contribution finding not produced.";
        }
        if (findings == 0) return "Aggregates computed but no findings met evidence thresholds.";
        return "Insufficient evidence to present analysis.";
    }

    public record EvidenceAssessment(
            boolean presentable,
            boolean strongFindings,
            double confidencePenalty,
            String reason
    ) {}
}
