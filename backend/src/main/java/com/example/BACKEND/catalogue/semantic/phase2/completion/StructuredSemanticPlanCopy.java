package com.example.BACKEND.catalogue.semantic.phase2.completion;

import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;

/**
 * Immutable updates to {@link StructuredSemanticPlan} during deterministic enrichment.
 */
final class StructuredSemanticPlanCopy {

    private StructuredSemanticPlanCopy() {}

    static StructuredSemanticPlan withSecondaryMetric(
            StructuredSemanticPlan plan,
            String secondaryMetric,
            String secondaryAggregation
    ) {
        StructuredSemanticPlan.SemanticAggregations aggregations = plan.aggregations();
        String primary = aggregations != null && aggregations.primary() != null
                ? aggregations.primary()
                : "SUM";
        String secondary = secondaryAggregation;
        if (secondary == null || secondary.isBlank()) {
            secondary = aggregations != null ? aggregations.secondary() : null;
        }
        if (secondary == null || secondary.isBlank()) {
            secondary = "SUM";
        }

        return new StructuredSemanticPlan(
                plan.intent(),
                plan.metric(),
                secondaryMetric,
                plan.dimensions(),
                plan.filters(),
                new StructuredSemanticPlan.SemanticAggregations(primary, secondary),
                plan.ordering(),
                plan.limit(),
                plan.relationshipVariable(),
                plan.timeGrain(),
                plan.confidence(),
                appendCompletionNote(plan.reasoning(), secondaryMetric),
                plan.alternatives());
    }

    private static String appendCompletionNote(String reasoning, String secondaryMetric) {
        String note = "Catalogue completion added denominator: " + secondaryMetric + ".";
        if (reasoning == null || reasoning.isBlank()) {
            return note;
        }
        if (reasoning.contains(secondaryMetric)) {
            return reasoning;
        }
        return reasoning + " " + note;
    }
}
