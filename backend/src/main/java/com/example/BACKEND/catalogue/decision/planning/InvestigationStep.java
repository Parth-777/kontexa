package com.example.BACKEND.catalogue.decision.planning;

import java.util.List;

/**
 * A single analytical step in an {@link InvestigationPlan}.
 *
 * Steps are ordered — earlier steps provide the analytical foundation
 * that later steps build upon. They are analytical operations, NOT SQL operations.
 *
 * Fields:
 *   stepNumber          — ordinal position (1-based)
 *   analyticalOperation — what this step computes (e.g. "RANK_BY_COMPOSITE_VALUE")
 *   purpose             — why this step is needed (executive rationale)
 *   requiredMetrics     — metrics this step depends on
 *   comparativeStrategy — how this step's results should be framed
 *   isRequired          — false = enrichment only, skip if evidence is thin
 */
public record InvestigationStep(
        int                  stepNumber,
        String               analyticalOperation,
        String               purpose,
        List<String>         requiredMetrics,
        ComparativeStrategy  comparativeStrategy,
        boolean              isRequired
) {}
