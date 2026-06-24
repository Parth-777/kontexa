package com.example.BACKEND.catalogue.decision.investigation;

/**
 * A single step in a question-specific investigation plan.
 */
public record InvestigationStep(
        int order,
        String stepKey,
        String title,
        String description
) {}
