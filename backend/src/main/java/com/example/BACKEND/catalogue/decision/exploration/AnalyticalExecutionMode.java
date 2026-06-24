package com.example.BACKEND.catalogue.decision.exploration;

/**
 * How the runtime executes an analytical question after planning.
 */
public enum AnalyticalExecutionMode {

    /** High planner confidence — strict semantic plan and governance. */
    STRICT_SEMANTIC,

    /** Medium confidence — semantic plan with heuristic materialization and soft governance. */
    HYBRID,

    /** Low confidence — heuristic interpretation, provisional findings, delayed strict validation. */
    EXPLORATORY_HEURISTIC;

    public boolean allowsSoftGovernance() {
        return this != STRICT_SEMANTIC;
    }

    public boolean prefersHeuristicInterpretation() {
        return this == EXPLORATORY_HEURISTIC;
    }
}
