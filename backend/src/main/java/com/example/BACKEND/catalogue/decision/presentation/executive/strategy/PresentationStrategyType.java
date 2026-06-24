package com.example.BACKEND.catalogue.decision.presentation.executive.strategy;

/**
 * Deterministic presentation shapes inferred from {@link com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel}.
 */
public enum PresentationStrategyType {
    SCALAR,
    RANKING,
    DISTRIBUTION,
    TREND,
    GROWTH,
    PARETO,
    OUTLIER,
    VARIANCE,
    CONTRIBUTION,
    COMPARISON,
    CORRELATION
}
