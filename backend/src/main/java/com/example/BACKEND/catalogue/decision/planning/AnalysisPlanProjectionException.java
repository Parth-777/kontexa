package com.example.BACKEND.catalogue.decision.planning;

/**
 * Thrown when {@link QueryDecompositionEngine} cannot project {@link InvestigationPlan}
 * metric binding fields verbatim from {@link AnalysisPlan}.
 */
public class AnalysisPlanProjectionException extends IllegalStateException {

    public AnalysisPlanProjectionException(String message) {
        super(message);
    }
}
