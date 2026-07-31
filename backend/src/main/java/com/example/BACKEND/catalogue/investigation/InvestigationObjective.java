package com.example.BACKEND.catalogue.investigation;

import java.util.List;

/**
 * Phase 2 planner contract — the only input a business investigation runtime needs.
 * Contrast-driven: every investigation compares a focal slice to a baseline slice.
 */
public record InvestigationObjective(
        Measure measure,
        Scope scope,
        Comparison comparison,
        Decomposition decomposition,
        String salience,
        double semanticConfidence
) {
    public static final String SALIENCE_FOCAL_HIGHER = "FOCAL_HIGHER";
    public static final String SALIENCE_FOCAL_LOWER = "FOCAL_LOWER";
    public static final String SALIENCE_DIFFERENCE = "DIFFERENCE";

    public static final String AGG_SUM = "SUM";
    public static final String AGG_COUNT = "COUNT";
    public static final String AGG_AVG = "AVG";

    public record Measure(String column, String aggregation, List<String> grain) {}

    public record Scope(List<Filter> filters) {}

    public record Comparison(Slice focal, Slice baseline) {}

    public record Slice(List<Constraint> constraints) {}

    /**
     * Composable slice bound — exactly one primitive per constraint entry.
     */
    public record Constraint(
            Filter filter,
            TimeRange timeRange,
            Rank rank,
            Boolean population
    ) {}

    public record Filter(String column, String operator, String value) {}

    public record TimeRange(String column, String start, String end) {}

    public record Rank(String column, String side, double fraction, List<String> grain) {}

    public record Decomposition(List<String> dimensions, List<String> exclude) {}
}
