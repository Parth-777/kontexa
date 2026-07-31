package com.example.BACKEND.catalogue.investigation;

import java.util.List;
import java.util.Set;

/**
 * Executable investigation plan after contrast resolution.
 */
public record ResolvedInvestigation(
        String question,
        String tenantId,
        String tableRef,
        InvestigationObjective objective,
        SlicePredicate focalPredicate,
        SlicePredicate baselinePredicate,
        String contrastDescription,
        Set<String> contrastColumns,
        List<CandidateDimension> candidateDimensions
) {}
