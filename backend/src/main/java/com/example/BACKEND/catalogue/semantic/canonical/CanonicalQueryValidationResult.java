package com.example.BACKEND.catalogue.semantic.canonical;

import java.util.List;

public record CanonicalQueryValidationResult(boolean valid, List<String> issues) {
    public static CanonicalQueryValidationResult ok() {
        return new CanonicalQueryValidationResult(true, List.of());
    }

    public static CanonicalQueryValidationResult fail(String issue) {
        return new CanonicalQueryValidationResult(false, List.of(issue));
    }

    public static CanonicalQueryValidationResult fail(List<String> issues) {
        return new CanonicalQueryValidationResult(false, List.copyOf(issues));
    }
}
