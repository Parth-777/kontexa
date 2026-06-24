package com.example.BACKEND.experiment.phase1;

import com.example.BACKEND.catalogue.decision.planning.AnalysisIntent;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.StructuredPlanProjection;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticDiscoveryDebug;

import java.util.List;

/**
 * Converts a validated Phase-1 candidate into a production {@link AnalysisPlan}
 * for reuse of the existing SQL generator (experiment adapter only).
 */
public final class Phase1AnalysisPlanAdapter {

    private Phase1AnalysisPlanAdapter() {}

    public static AnalysisPlan toAnalysisPlan(
            String question,
            String tableRef,
            Phase1PlannerCandidate candidate
    ) {
        if (candidate.metric() == null || candidate.metric().isBlank()) {
            return AnalysisPlan.blocked(question, "LLM did not resolve metric");
        }
        AnalysisIntent intent = candidate.intent();
        if (intent == AnalysisIntent.RELATIONSHIP) {
            return AnalysisPlan.blocked(question, "Relationship queries out of Phase-1 scope");
        }

        String dimension = candidate.dimensions() != null && !candidate.dimensions().isEmpty()
                ? candidate.dimensions().get(0) : null;

        if (intent != AnalysisIntent.CONTRIBUTION && (dimension == null || dimension.isBlank())) {
            if (intent == AnalysisIntent.RANKING) {
                dimension = firstCategoricalFallback(candidate);
            }
        }

        boolean needsDimension = intent != AnalysisIntent.CONTRIBUTION || dimension != null;
        if (needsDimension && (dimension == null || dimension.isBlank())
                && intent != AnalysisIntent.CONTRIBUTION) {
            return AnalysisPlan.blocked(question, "LLM did not resolve grouping dimension");
        }

        String grouping = dimension;
        return new AnalysisPlan(
                question,
                tableRef,
                intent,
                candidate.metric(),
                humanize(candidate.metric()),
                dimension,
                dimension != null ? humanize(dimension) : null,
                grouping,
                null,
                null,
                null,
                null,
                true,
                List.of(),
                SemanticDiscoveryDebug.empty(null),
                List.of(),
                StructuredPlanProjection.empty());
    }

    private static String firstCategoricalFallback(Phase1PlannerCandidate candidate) {
        if (candidate.dimensions() == null || candidate.dimensions().isEmpty()) return null;
        return candidate.dimensions().get(0);
    }

    private static String humanize(String col) {
        return col == null ? null : col.replace('_', ' ');
    }
}
