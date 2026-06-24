package com.example.BACKEND.catalogue.decision.investigation;

/**
 * Phase 1 output — metrics, business entity, and intent extracted from the question.
 */
public record ExtractedQuestionEntities(
        String question,
        String metricKey,
        String metricLabel,
        String targetMetricKey,
        String businessEntityPhrase,
        String businessEntityKey,
        AnalyticalInvestigationIntent intent,
        double confidence
) {
    public boolean hasBusinessEntity() {
        return businessEntityKey != null && !businessEntityKey.isBlank();
    }

    public boolean isShareAnalysis() {
        return intent == AnalyticalInvestigationIntent.SHARE_OF_TOTAL;
    }

    public boolean isRelationshipAnalysis() {
        return intent == AnalyticalInvestigationIntent.RELATIONSHIP;
    }
}
