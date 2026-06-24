package com.example.BACKEND.catalogue.decision.grounding;

/**
 * A semantically classified analytical field with a business-readable display name.
 */
public record SemanticFieldRef(
        String rawKey,
        String displayLabel,
        SemanticFieldKind kind
) {
    public boolean isMetric() { return kind == SemanticFieldKind.METRIC; }
    public boolean isDimension() { return kind.isDimension(); }
}
