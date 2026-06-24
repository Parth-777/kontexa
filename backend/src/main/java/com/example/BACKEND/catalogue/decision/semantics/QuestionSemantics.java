package com.example.BACKEND.catalogue.decision.semantics;

import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;

import java.util.List;

/**
 * Structured extraction of what the user is actually asking.
 */
public record QuestionSemantics(
        String                 question,
        String                 primaryMetric,
        String                 primaryMetricLabel,
        String                 targetMetric,
        String                 targetMetricLabel,
        String                 dimension,
        String                 dimensionLabel,
        String                 grouping,
        AnalyticalIntentType   intent,
        AnalyticalRelationship relationship,
        List<String>           temporalReferences,
        double                 confidence,
        List<String>           extractedEntities
) {
    public boolean hasDimension() {
        return dimension != null && !dimension.isBlank();
    }

    public boolean hasTargetMetric() {
        return targetMetric != null && !targetMetric.isBlank();
    }

    public static QuestionSemantics unresolved(String question) {
        return new QuestionSemantics(
                question, null, null, null, null, null, null, null,
                AnalyticalIntentType.GENERAL_ANALYSIS, AnalyticalRelationship.DIMENSION_BREAKDOWN,
                List.of(), 0.2, List.of());
    }
}
