package com.example.BACKEND.catalogue.semantic.canonical;

import java.util.List;

/**
 * Faithful projection of GPT planner output — no intent routing, inference, or SQL semantics.
 * Used only for observability and fidelity measurement against {@link com.example.BACKEND.catalogue.decision.planning.AnalysisPlan}.
 */
public record CanonicalQueryModel(
        MeasureSpec measure,
        PartitionSpec partition,
        List<FilterSpec> filters,
        RatioSpec ratio,
        BivariateSpec bivariate,
        OrderSpec ordering,
        Integer limit,
        PlannerMetadata metadata
) {
    public record MeasureSpec(String column, String aggregation) {}

    public record PartitionSpec(String column, String timeGrain) {}

    public record FilterSpec(String column, String operator, String value) {}

    public record RatioSpec(String kind, MeasureSpec denominator) {}

    public record BivariateSpec(String columnA, String columnB, String function) {}

    public record OrderSpec(String column, String direction) {}

    public record PlannerMetadata(
            String intent,
            double confidence,
            String reasoning,
            List<String> dimensions,
            String secondaryMetric,
            String relationshipVariable
    ) {}

    public static CanonicalQueryModel empty() {
        return new CanonicalQueryModel(
                null, null, List.of(), null, null, null, null,
                new PlannerMetadata(null, 0.0, null, List.of(), null, null));
    }
}
