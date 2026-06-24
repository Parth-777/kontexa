package com.example.BACKEND.catalogue.decision.semantic;

/**
 * A single entity resolved from question text.
 */
public record ResolvedEntity(
        String     phrase,
        String     columnKey,
        String     label,
        EntityKind kind,
        double     matchScore
) {}
