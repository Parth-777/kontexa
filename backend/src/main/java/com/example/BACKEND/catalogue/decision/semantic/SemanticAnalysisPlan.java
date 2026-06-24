package com.example.BACKEND.catalogue.decision.semantic;

import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.semantic.AnalyticalIntentPatterns.PatternKind;

import java.util.List;

/**
 * Fully parsed semantic analysis plan produced before warehouse planning.
 */
public record SemanticAnalysisPlan(
        boolean                    parsed,
        double                     confidence,
        PatternKind                patternKind,
        AnalyticalIntentType       intent,
        String                     primaryMetric,
        String                     primaryMetricLabel,
        String                     secondaryMetric,
        String                     groupingDimension,
        String                     groupingLabel,
        ContributionAnalysisPlan   contributionPlan,
        DimensionImpactPlan        dimensionImpactPlan,
        List<ResolvedEntity>       resolvedEntities,
        String                     planSummary,
        String                     failureReason
) {
    public static SemanticAnalysisPlan failure(String reason) {
        return new SemanticAnalysisPlan(
                false, 0.35, PatternKind.CONTRIBUTION, AnalyticalIntentType.GENERAL_ANALYSIS,
                null, null, null, null, null,
                null, null, List.of(), "", reason);
    }

    public boolean hasGrouping() {
        return groupingDimension != null && !groupingDimension.isBlank();
    }

    public boolean isCompositionRatio() {
        return contributionPlan != null && !hasGrouping();
    }
}
