package com.example.BACKEND.catalogue.decision.investigation;

import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticCatalog;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticDiscoveryDebug;

import java.util.List;

/**
 * Full investigation artifact: extracted entities, resolved dimension, and executable plan.
 */
public record QuestionInvestigation(
        ExtractedQuestionEntities extraction,
        ResolvedDimension dimension,
        List<InvestigationStep> steps,
        boolean executable,
        SemanticCatalog catalog,
        SemanticDiscoveryDebug discovery
) {
    public QuestionInvestigation(
            ExtractedQuestionEntities extraction,
            ResolvedDimension dimension,
            List<InvestigationStep> steps,
            boolean executable
    ) {
        this(extraction, dimension, steps, executable, null, null);
    }

    public String blockingReason() {
        if (executable) return null;
        if (dimension != null && dimension.failureMessage() != null) {
            return dimension.failureMessage();
        }
        if (extraction != null && extraction.isShareAnalysis()) {
            return null;
        }
        return "Investigation could not be planned for this question.";
    }
}
