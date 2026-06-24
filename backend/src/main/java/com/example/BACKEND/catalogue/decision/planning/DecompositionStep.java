package com.example.BACKEND.catalogue.decision.planning;

/**
 * One analytical operation in a question-aware decomposition plan.
 * Steps are intellectual operations — not SQL — that drive materialization and findings.
 */
public record DecompositionStep(
        int     order,
        String  stepKey,
        String  purpose,
        String  outputMetric,
        boolean required
) {}
