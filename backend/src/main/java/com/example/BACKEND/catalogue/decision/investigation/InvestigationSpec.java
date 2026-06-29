package com.example.BACKEND.catalogue.decision.investigation;

import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;

import java.util.List;

/**
 * Resolved plan for a single CHANGE-mode, dimension-driver investigation.
 *
 * <p>Built additively from the canonical plan: the target measure and base filters are
 * reused verbatim, baseline/observation windows are derived generically, and candidate
 * dimensions come from the approved catalogue.
 */
public record InvestigationSpec(
        String question,
        String qualifiedTableName,
        CanonicalQueryModel.MeasureSpec targetMeasure,
        String timeColumn,
        String grain,
        TimeWindow baselineWindow,
        TimeWindow observationWindow,
        String direction,
        List<CanonicalQueryModel.FilterSpec> baseFilters,
        List<CandidateDimension> candidateDimensions,
        boolean applicable,
        String inapplicableReason
) {
    public static InvestigationSpec notApplicable(String question, String reason) {
        return new InvestigationSpec(
                question, null, null, null, null, null, null, null,
                List.of(), List.of(), false, reason);
    }
}
