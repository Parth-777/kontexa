package com.example.BACKEND.catalogue.decision.semantic;

/**
 * Classification of analytical entities extracted from natural language.
 */
public enum EntityKind {
    METRIC,
    DIMENSION,
    TEMPORAL_DIMENSION,
    FILTER,
    DERIVED_METRIC
}
