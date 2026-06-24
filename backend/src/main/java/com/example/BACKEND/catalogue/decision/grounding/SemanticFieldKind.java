package com.example.BACKEND.catalogue.decision.grounding;

/**
 * Explicit classification for every field in an analytical decomposition.
 * Dimensions must never be narrated as value-producing metrics.
 */
public enum SemanticFieldKind {
    METRIC,
    DIMENSION,
    TEMPORAL_DIMENSION,
    CATEGORICAL_DIMENSION,
    IDENTIFIER;

    public boolean isDimension() {
        return this == DIMENSION || this == TEMPORAL_DIMENSION || this == CATEGORICAL_DIMENSION;
    }
}
