package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutablePlanValidatorTest {

    private final ExecutablePlanValidator validator = new ExecutablePlanValidator();

    @Test
    void validateRows_acceptsAnyNonEmptyResult() {
        assertFalse(validator.validateRows(List.of()).valid());
        assertTrue(validator.validateRows(List.of(Map.of("a", 1))).valid());
        assertTrue(validator.validateRows(List.of(
                Map.of("bucket", "0-1", "revenue", 100),
                Map.of("bucket", "1-3", "revenue", 200)
        )).valid());
    }
}
