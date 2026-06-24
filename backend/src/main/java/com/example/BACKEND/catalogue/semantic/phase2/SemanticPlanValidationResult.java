package com.example.BACKEND.catalogue.semantic.phase2;

import java.util.List;

public record SemanticPlanValidationResult(
        boolean valid,
        List<String> issues
) {
    public static SemanticPlanValidationResult ok() {
        return new SemanticPlanValidationResult(true, List.of());
    }

    public static SemanticPlanValidationResult fail(String issue) {
        return new SemanticPlanValidationResult(false, List.of(issue));
    }

    public static SemanticPlanValidationResult fail(List<String> issues) {
        return new SemanticPlanValidationResult(false, List.copyOf(issues));
    }
}
