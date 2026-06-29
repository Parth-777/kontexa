package com.example.BACKEND.catalogue.decision.investigation;

/**
 * A half-open time range [startInclusive, endExclusive) on a single time column.
 * Boundaries are SQL date literals (e.g. {@code 2026-06-01}).
 */
public record TimeWindow(
        String column,
        String startInclusive,
        String endExclusive
) {}
