package com.example.BACKEND.catalogue.semantic.phase2;

import java.util.List;

/**
 * GPT structured semantic plan — mirrors Phase-2 JSON contract.
 */
public record StructuredSemanticPlan(
        String intent,
        String metric,
        String secondaryMetric,
        List<String> dimensions,
        List<SemanticFilter> filters,
        SemanticAggregations aggregations,
        SemanticOrdering ordering,
        Integer limit,
        String relationshipVariable,
        String timeGrain,
        double confidence,
        String reasoning,
        List<StructuredSemanticPlan> alternatives,
        String executionMode,
        String investigationDirection
) {
    public static final String MODE_CANONICAL = "CANONICAL";
    public static final String MODE_INVESTIGATION = "INVESTIGATION";

    /**
     * Backward-compatible constructor: routing defaults to {@link #MODE_CANONICAL}
     * so any plan produced without an explicit execution mode follows the unchanged
     * Phase-1 canonical path.
     */
    public StructuredSemanticPlan(
            String intent,
            String metric,
            String secondaryMetric,
            List<String> dimensions,
            List<SemanticFilter> filters,
            SemanticAggregations aggregations,
            SemanticOrdering ordering,
            Integer limit,
            String relationshipVariable,
            String timeGrain,
            double confidence,
            String reasoning,
            List<StructuredSemanticPlan> alternatives
    ) {
        this(intent, metric, secondaryMetric, dimensions, filters, aggregations,
                ordering, limit, relationshipVariable, timeGrain, confidence,
                reasoning, alternatives, MODE_CANONICAL, null);
    }

    public boolean requiresInvestigation() {
        return MODE_INVESTIGATION.equalsIgnoreCase(executionMode);
    }

    public record SemanticFilter(String column, String operator, String value) {}

    public record SemanticOrdering(String column, String direction) {}

    public record SemanticAggregations(String primary, String secondary) {}

    public static StructuredSemanticPlan empty(String reasoning) {
        return new StructuredSemanticPlan(
                "DISTRIBUTION", null, null, List.of(), List.of(),
                new SemanticAggregations(null, null), null, null, null, null,
                0.0, reasoning, List.of());
    }
}
