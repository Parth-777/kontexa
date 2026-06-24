package com.example.BACKEND.catalogue.decision.transforms;

/**
 * Executable warehouse dimension derived from a semantic concept.
 */
public record DerivedDimensionSpec(
        SemanticConcept       concept,
        String                logicalKey,
        String                sourceColumn,
        String                bucketExpression,
        String                outputAlias,
        boolean               requiresDerivation,
        java.util.List<TransformationStep> steps
) {
    public boolean isExecutable() {
        return bucketExpression != null && !bucketExpression.isBlank()
                && outputAlias != null && !outputAlias.isBlank();
    }
}
