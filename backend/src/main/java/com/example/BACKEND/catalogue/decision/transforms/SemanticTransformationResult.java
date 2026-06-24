package com.example.BACKEND.catalogue.decision.transforms;

import com.example.BACKEND.catalogue.decision.execution.sqltemplates.TemplateContext;

import java.util.List;

/**
 * Output of semantic → SQL transformation including trace steps.
 */
public record SemanticTransformationResult(
        DerivedDimensionSpec    dimension,
        TemplateContext         templateContext,
        List<TransformationStep> traceSteps,
        boolean                 success,
        String                  failureReason
) {
    public static SemanticTransformationResult failed(String reason) {
        return new SemanticTransformationResult(null, null, List.of(), false, reason);
    }

    public static SemanticTransformationResult ok(
            DerivedDimensionSpec dim, TemplateContext ctx, List<TransformationStep> steps
    ) {
        return new SemanticTransformationResult(dim, ctx, steps, true, null);
    }
}
