package com.example.BACKEND.experiment.phase1;

/**
 * Column filter proposed by the LLM planner (experiment-only; not part of production contracts).
 */
public record Phase1FilterSpec(
        String column,
        String operator,
        String value
) {}
