package com.example.BACKEND.catalogue.investigation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Verifies that an {@link InvestigationObjective} can be executed against catalogue evidence.
 */
@Component
public class ApplicabilityEvaluator {

    public ApplicabilityResult evaluate(InvestigationObjective objective, String tableRef) {
        if (objective == null) {
            return ApplicabilityResult.notApplicable("missing investigation_objective from planner");
        }
        if (tableRef == null || tableRef.isBlank()) {
            return ApplicabilityResult.notApplicable("no resolved table reference");
        }
        InvestigationObjective.Measure measure = objective.measure();
        if (measure == null || measure.column() == null || measure.column().isBlank()) {
            return ApplicabilityResult.notApplicable("no resolved measure column");
        }
        String agg = normalizeAgg(measure.aggregation());
        if (agg == null) {
            return ApplicabilityResult.notApplicable(
                    "unsupported aggregation (supported: SUM, COUNT, AVG)");
        }
        if (objective.comparison() == null
                || objective.comparison().focal() == null
                || objective.comparison().baseline() == null) {
            return ApplicabilityResult.notApplicable("comparison requires focal and baseline slices");
        }
        if (slicesEqual(objective.comparison().focal(), objective.comparison().baseline())) {
            return ApplicabilityResult.notApplicable("focal and baseline slices are identical");
        }
        return ApplicabilityResult.applicable();
    }

    public ApplicabilityResult evaluateResolved(ResolvedInvestigation resolved) {
        ApplicabilityResult base = evaluate(resolved.objective(), resolved.tableRef());
        if (!base.ok()) {
            return base;
        }
        if (resolved.candidateDimensions() == null || resolved.candidateDimensions().isEmpty()) {
            return ApplicabilityResult.notApplicable("no eligible catalogue dimensions for decomposition");
        }
        if (resolved.focalPredicate() == null || resolved.baselinePredicate() == null) {
            return ApplicabilityResult.notApplicable("contrast slices could not be resolved");
        }
        return ApplicabilityResult.applicable();
    }

    private boolean slicesEqual(InvestigationObjective.Slice a, InvestigationObjective.Slice b) {
        if (a == null || b == null) {
            return false;
        }
        List<InvestigationObjective.Constraint> ac = a.constraints() == null ? List.of() : a.constraints();
        List<InvestigationObjective.Constraint> bc = b.constraints() == null ? List.of() : b.constraints();
        return ac.equals(bc);
    }

    private String normalizeAgg(String aggregation) {
        if (aggregation == null || aggregation.isBlank()) {
            return InvestigationObjective.AGG_SUM;
        }
        String a = aggregation.trim().toUpperCase(Locale.ROOT);
        if (InvestigationObjective.AGG_SUM.equals(a)
                || InvestigationObjective.AGG_COUNT.equals(a)
                || InvestigationObjective.AGG_AVG.equals(a)) {
            return a;
        }
        return null;
    }

    public record ApplicabilityResult(boolean ok, String reason) {
        public static ApplicabilityResult applicable() {
            return new ApplicabilityResult(true, null);
        }

        public static ApplicabilityResult notApplicable(String reason) {
            return new ApplicabilityResult(false, reason);
        }
    }
}
