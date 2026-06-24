package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Lenient validation during stabilization — only hard-fail on total absence of data.
 */
@Component
public class ExecutablePlanValidator {

    public record ValidationResult(
            boolean valid,
            List<String> issues
    ) {
        public static ValidationResult pass() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult fail(List<String> issues) {
            return new ValidationResult(false, issues);
        }
    }

    public ValidationResult validate(MaterializedQueryResult result) {
        if (result == null) {
            return ValidationResult.fail(List.of("No materialized result"));
        }
        if (result.hasContent()) {
            return ValidationResult.pass();
        }
        if (result.totalRows() > 0) {
            return ValidationResult.pass();
        }
        return ValidationResult.fail(List.of("No analytical result returned"));
    }

    public ValidationResult validateRows(List<java.util.Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return ValidationResult.fail(List.of("Query returned 0 rows"));
        }
        return ValidationResult.pass();
    }
}
