package com.example.BACKEND.catalogue.decision.planning;

import java.util.List;

/**
 * Execution-relevant fields propagated from a GPT {@code StructuredSemanticPlan}
 * into {@link AnalysisPlan} so SQL generation does not lose planner intent.
 */
public record StructuredPlanProjection(
        List<String> dimensions,
        String primaryAggregation,
        String secondaryAggregation,
        String orderColumn,
        String orderDirection,
        Integer resultLimit,
        String timeGrain
) {
    public static StructuredPlanProjection empty() {
        return new StructuredPlanProjection(List.of(), null, null, null, null, null, null);
    }

    public boolean hasOrdering() {
        return orderColumn != null && !orderColumn.isBlank();
    }

    public boolean hasTimeGrain() {
        return timeGrain != null && !timeGrain.isBlank();
    }

    public boolean hasPrimaryAggregation() {
        return primaryAggregation != null && !primaryAggregation.isBlank();
    }
}
