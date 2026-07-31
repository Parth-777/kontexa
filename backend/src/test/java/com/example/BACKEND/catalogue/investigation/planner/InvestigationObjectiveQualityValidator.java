package com.example.BACKEND.catalogue.investigation.planner;

import com.example.BACKEND.catalogue.investigation.InvestigationObjective;
import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates GPT-produced {@link InvestigationObjective} instances for planner quality.
 * Test-only — does not affect runtime execution.
 */
public final class InvestigationObjectiveQualityValidator {

    private static final double AMBIGUITY_THRESHOLD = 0.7;
    private static final Set<String> VALID_SALIENCE = Set.of(
            InvestigationObjective.SALIENCE_FOCAL_HIGHER,
            InvestigationObjective.SALIENCE_FOCAL_LOWER,
            InvestigationObjective.SALIENCE_DIFFERENCE);

    public ValidationResult validate(
            String question,
            StructuredSemanticPlan plan,
            InvestigationObjective objective,
            ApprovedCatalogueSnapshot catalogue) {

        List<QualityIssue> issues = new ArrayList<>();

        if (plan == null) {
            issues.add(issue("MISSING_PLAN", "planner returned null"));
            return result(question, plan, objective, issues);
        }

        String mode = plan.executionMode();
        if (!StructuredSemanticPlan.MODE_INVESTIGATION.equalsIgnoreCase(mode)) {
            issues.add(issue("UNEXPECTED_EXECUTION_MODE",
                    "expected INVESTIGATION but got " + mode));
        }

        if (objective == null) {
            issues.add(issue("MISSING_INVESTIGATION_OBJECTIVE",
                    "investigation_objective absent or unparseable"));
            return result(question, plan, objective, issues);
        }

        validateContractCompleteness(objective, issues);
        validateCatalogueBindings(objective, catalogue, issues);
        validateComparisonConstruction(objective, issues);
        validateExclusionRules(objective, issues);

        if (objective.semanticConfidence() < AMBIGUITY_THRESHOLD) {
            issues.add(issue("LOW_SEMANTIC_CONFIDENCE",
                    "semantic_confidence=" + objective.semanticConfidence()
                            + " below " + AMBIGUITY_THRESHOLD));
        }

        return result(question, plan, objective, issues);
    }

    private void validateContractCompleteness(InvestigationObjective o, List<QualityIssue> issues) {
        if (o.measure() == null || blank(o.measure().column())) {
            issues.add(issue("MISSING_MEASURE_COLUMN", "measure.column is required"));
        }
        if (o.measure() != null && normalizeAgg(o.measure().aggregation()) == null) {
            issues.add(issue("INVALID_AGGREGATION",
                    "aggregation must be SUM, COUNT, or AVG (got "
                            + o.measure().aggregation() + ")"));
        }
        if (o.scope() == null) {
            issues.add(issue("MISSING_SCOPE", "scope is required"));
        }
        if (o.comparison() == null) {
            issues.add(issue("MISSING_COMPARISON", "comparison is required"));
            return;
        }
        if (o.comparison().focal() == null) {
            issues.add(issue("MISSING_FOCAL_SLICE", "comparison.focal is required"));
        }
        if (o.comparison().baseline() == null) {
            issues.add(issue("MISSING_BASELINE_SLICE", "comparison.baseline is required"));
        }
        if (o.decomposition() == null) {
            issues.add(issue("MISSING_DECOMPOSITION", "decomposition is required"));
        } else if (o.decomposition().exclude() == null || o.decomposition().exclude().isEmpty()) {
            issues.add(issue("MISSING_EXCLUDE_LIST", "decomposition.exclude must be non-empty"));
        }
        if (blank(o.salience()) || !VALID_SALIENCE.contains(o.salience())) {
            issues.add(issue("INVALID_SALIENCE", "salience must be FOCAL_HIGHER, FOCAL_LOWER, or DIFFERENCE"));
        }
    }

    private void validateCatalogueBindings(
            InvestigationObjective o,
            ApprovedCatalogueSnapshot catalogue,
            List<QualityIssue> issues) {
        if (catalogue == null || o.measure() == null) {
            return;
        }
        bindColumn(o.measure().column(), "measure.column", catalogue, issues);
        if (o.measure().grain() != null) {
            for (String g : o.measure().grain()) {
                bindColumn(g, "measure.grain", catalogue, issues);
            }
        }
        if (o.scope() != null && o.scope().filters() != null) {
            for (InvestigationObjective.Filter f : o.scope().filters()) {
                bindColumn(f.column(), "scope.filter", catalogue, issues);
            }
        }
        collectSliceColumns(o.comparison() != null ? o.comparison().focal() : null, "focal", catalogue, issues);
        collectSliceColumns(o.comparison() != null ? o.comparison().baseline() : null, "baseline", catalogue, issues);
        if (o.decomposition() != null && o.decomposition().dimensions() != null) {
            for (String d : o.decomposition().dimensions()) {
                bindColumn(d, "decomposition.dimension", catalogue, issues);
            }
        }
    }

    private void validateComparisonConstruction(InvestigationObjective o, List<QualityIssue> issues) {
        if (o.comparison() == null) {
            return;
        }
        InvestigationObjective.Slice focal = o.comparison().focal();
        InvestigationObjective.Slice baseline = o.comparison().baseline();
        if (focal != null && emptySlice(focal)) {
            issues.add(issue("EMPTY_FOCAL_SLICE", "focal.constraints is empty"));
        }
        if (baseline != null && emptySlice(baseline)) {
            issues.add(issue("EMPTY_BASELINE_SLICE",
                    "baseline.constraints is empty (use population primitive)"));
        }
        if (focal != null && baseline != null && slicesEqual(focal, baseline)) {
            issues.add(issue("IDENTICAL_SLICES", "focal and baseline constraints are identical"));
        }
        validateSliceConstraints(focal, "focal", issues);
        validateSliceConstraints(baseline, "baseline", issues);
    }

    private void validateExclusionRules(InvestigationObjective o, List<QualityIssue> issues) {
        if (o.decomposition() == null || o.measure() == null) {
            return;
        }
        Set<String> exclude = lowerSet(o.decomposition().exclude());
        if (exclude.isEmpty()) {
            return;
        }
        String measure = lower(o.measure().column());
        if (measure != null && !exclude.contains(measure)) {
            issues.add(issue("EXCLUDE_MISSING_MEASURE",
                    "decomposition.exclude must contain measure.column=" + o.measure().column()));
        }
        Set<String> contrastCols = contrastColumns(o);
        for (String col : contrastCols) {
            if (!exclude.contains(lower(col))) {
                issues.add(issue("EXCLUDE_MISSING_CONTRAST_COLUMN",
                        "decomposition.exclude must contain contrast column " + col));
            }
        }
        if (o.decomposition().dimensions() != null) {
            for (String dim : o.decomposition().dimensions()) {
                if (contrastCols.contains(lower(dim))) {
                    issues.add(issue("DECOMPOSITION_INCLUDES_CONTRAST_COLUMN",
                            "decomposition.dimensions must not include contrast column " + dim));
                }
                if (measure != null && measure.equals(lower(dim))) {
                    issues.add(issue("DECOMPOSITION_INCLUDES_MEASURE",
                            "decomposition.dimensions must not include measure.column"));
                }
            }
        }
    }

    private void validateSliceConstraints(
            InvestigationObjective.Slice slice, String label, List<QualityIssue> issues) {
        if (slice == null || slice.constraints() == null) {
            return;
        }
        for (InvestigationObjective.Constraint c : slice.constraints()) {
            if (c == null) {
                issues.add(issue("MALFORMED_CONSTRAINT", label + " has null constraint entry"));
                continue;
            }
            int primitives = divCount(c);
            if (primitives == 0) {
                issues.add(issue("MALFORMED_CONSTRAINT", label + " constraint has no primitive"));
            }
            if (primitives > 1) {
                issues.add(issue("MULTI_PRIMITIVE_CONSTRAINT",
                        label + " constraint should have exactly one primitive"));
            }
            if (c.timeRange() != null) {
                InvestigationObjective.TimeRange t = c.timeRange();
                if (blank(t.column()) || blank(t.start()) || blank(t.end())) {
                    issues.add(issue("INCOMPLETE_TIME_RANGE",
                            label + " time_range missing column/start/end"));
                } else if (!isValidDateOrder(t.start(), t.end())) {
                    issues.add(issue("INVALID_TIME_RANGE",
                            label + " time_range start must be before end"));
                }
            }
            if (c.rank() != null) {
                InvestigationObjective.Rank r = c.rank();
                if (blank(r.column())) {
                    issues.add(issue("INCOMPLETE_RANK", label + " rank missing column"));
                }
                if (r.fraction() <= 0 || r.fraction() > 1.0) {
                    issues.add(issue("INVALID_RANK_FRACTION",
                            label + " rank.fraction must be in (0, 1]"));
                }
                String side = r.side() == null ? "" : r.side().toUpperCase(Locale.ROOT);
                if (!side.equals("LOWER") && !side.equals("UPPER")) {
                    issues.add(issue("INVALID_RANK_SIDE", label + " rank.side must be LOWER or UPPER"));
                }
            }
        }
    }

    private void collectSliceColumns(
            InvestigationObjective.Slice slice,
            String label,
            ApprovedCatalogueSnapshot catalogue,
            List<QualityIssue> issues) {
        if (slice == null || slice.constraints() == null) {
            return;
        }
        for (InvestigationObjective.Constraint c : slice.constraints()) {
            if (c == null) {
                continue;
            }
            if (c.filter() != null) {
                bindColumn(c.filter().column(), label + ".filter", catalogue, issues);
            }
            if (c.timeRange() != null) {
                bindColumn(c.timeRange().column(), label + ".time_range", catalogue, issues);
            }
            if (c.rank() != null) {
                bindColumn(c.rank().column(), label + ".rank", catalogue, issues);
                if (c.rank().grain() != null) {
                    for (String g : c.rank().grain()) {
                        bindColumn(g, label + ".rank.grain", catalogue, issues);
                    }
                }
            }
        }
    }

    private Set<String> contrastColumns(InvestigationObjective o) {
        Set<String> cols = new LinkedHashSet<>();
        addSliceColumns(o.comparison() != null ? o.comparison().focal() : null, cols);
        addSliceColumns(o.comparison() != null ? o.comparison().baseline() : null, cols);
        return cols;
    }

    private void addSliceColumns(InvestigationObjective.Slice slice, Set<String> cols) {
        if (slice == null || slice.constraints() == null) {
            return;
        }
        for (InvestigationObjective.Constraint c : slice.constraints()) {
            if (c == null) {
                continue;
            }
            if (c.filter() != null && c.filter().column() != null) {
                cols.add(lower(c.filter().column()));
            }
            if (c.timeRange() != null && c.timeRange().column() != null) {
                cols.add(lower(c.timeRange().column()));
            }
            if (c.rank() != null) {
                if (c.rank().column() != null) {
                    cols.add(lower(c.rank().column()));
                }
                if (c.rank().grain() != null) {
                    for (String g : c.rank().grain()) {
                        cols.add(lower(g));
                    }
                }
            }
        }
    }

    private void bindColumn(
            String column,
            String context,
            ApprovedCatalogueSnapshot catalogue,
            List<QualityIssue> issues) {
        if (blank(column)) {
            issues.add(issue("BLANK_COLUMN_REFERENCE", context + " has blank column"));
            return;
        }
        if (!catalogue.hasColumn(column)) {
            issues.add(issue("UNBOUND_COLUMN", context + " references unknown column " + column));
        }
    }

    private static ValidationResult result(
            String question,
            StructuredSemanticPlan plan,
            InvestigationObjective objective,
            List<QualityIssue> issues) {
        boolean contractOk = issues.stream().noneMatch(i -> i.code().startsWith("MISSING_")
                || i.code().startsWith("INVALID_") || i.code().equals("MALFORMED_CONSTRAINT"));
        boolean bindingOk = issues.stream().noneMatch(i -> i.code().startsWith("UNBOUND_")
                || i.code().equals("BLANK_COLUMN_REFERENCE"));
        boolean comparisonOk = issues.stream().noneMatch(i -> i.code().contains("SLICE")
                || i.code().contains("TIME_RANGE") || i.code().contains("RANK"));
        boolean exclusionOk = issues.stream().noneMatch(i -> i.code().startsWith("EXCLUDE_")
                || i.code().startsWith("DECOMPOSITION_INCLUDES"));
        boolean pass = issues.isEmpty();
        return new ValidationResult(
                question,
                plan != null ? plan.executionMode() : null,
                objective,
                issues,
                pass,
                contractOk,
                bindingOk,
                comparisonOk,
                exclusionOk);
    }

    private static QualityIssue issue(String code, String detail) {
        return new QualityIssue(code, detail);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static Set<String> lowerSet(List<String> cols) {
        Set<String> out = new LinkedHashSet<>();
        if (cols != null) {
            for (String c : cols) {
                if (c != null) {
                    out.add(c.toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static String normalizeAgg(String agg) {
        if (agg == null || agg.isBlank()) {
            return InvestigationObjective.AGG_SUM;
        }
        String a = agg.trim().toUpperCase(Locale.ROOT);
        if (InvestigationObjective.AGG_SUM.equals(a)
                || InvestigationObjective.AGG_COUNT.equals(a)
                || InvestigationObjective.AGG_AVG.equals(a)) {
            return a;
        }
        return null;
    }

    private static boolean emptySlice(InvestigationObjective.Slice slice) {
        return slice.constraints() == null || slice.constraints().isEmpty();
    }

    private static boolean slicesEqual(InvestigationObjective.Slice a, InvestigationObjective.Slice b) {
        List<InvestigationObjective.Constraint> ac = a.constraints() == null ? List.of() : a.constraints();
        List<InvestigationObjective.Constraint> bc = b.constraints() == null ? List.of() : b.constraints();
        return ac.equals(bc);
    }

    private static int divCount(InvestigationObjective.Constraint c) {
        int n = 0;
        if (c.filter() != null) n++;
        if (c.timeRange() != null) n++;
        if (c.rank() != null) n++;
        if (Boolean.TRUE.equals(c.population())) n++;
        return n;
    }

    private static boolean isValidDateOrder(String start, String end) {
        try {
            String s = start.length() >= 10 ? start.substring(0, 10) : start;
            String e = end.length() >= 10 ? end.substring(0, 10) : end;
            return !LocalDate.parse(s).isAfter(LocalDate.parse(e));
        } catch (RuntimeException ex) {
            return true;
        }
    }

    public record QualityIssue(String code, String detail) {}

    public record ValidationResult(
            String question,
            String executionMode,
            InvestigationObjective objective,
            List<QualityIssue> issues,
            boolean pass,
            boolean contractComplete,
            boolean catalogueBound,
            boolean comparisonValid,
            boolean exclusionValid
    ) {
        boolean ambiguous() {
            return issues.stream().anyMatch(i -> i.code().equals("LOW_SEMANTIC_CONFIDENCE"));
        }
    }
}
