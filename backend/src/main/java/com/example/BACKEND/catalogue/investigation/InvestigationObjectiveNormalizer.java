package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Contract hygiene layer between planner output and runtime execution.
 *
 * <p>Automatically repairs decomposition exclude/dimension lists and rejects
 * objectives that cannot be executed (identical slices, unbound columns).
 * Does not invent new investigation types or routing.
 */
@Component
public class InvestigationObjectiveNormalizer {

    public NormalizationResult normalize(InvestigationObjective raw, ApprovedCatalogueSnapshot catalogue) {
        return normalize(raw, columnExistsPredicate(catalogue));
    }

    public NormalizationResult normalize(InvestigationObjective raw, RegistryResolutionBundle bundle) {
        return normalize(raw, columnExistsPredicate(bundle));
    }

    public NormalizationResult normalize(InvestigationObjective raw, Predicate<String> columnExists) {
        if (raw == null) {
            return NormalizationResult.reject("missing investigation_objective");
        }
        if (raw.measure() == null || blank(raw.measure().column())) {
            return NormalizationResult.reject("missing measure.column");
        }
        if (raw.comparison() == null
                || raw.comparison().focal() == null
                || raw.comparison().baseline() == null) {
            return NormalizationResult.reject("comparison requires focal and baseline slices");
        }
        if (slicesEqual(raw.comparison().focal(), raw.comparison().baseline())) {
            return NormalizationResult.reject("identical focal and baseline slices");
        }

        List<String> unbound = unboundColumns(raw, columnExists);
        if (!unbound.isEmpty()) {
            return NormalizationResult.reject("unbound catalogue columns: " + String.join(", ", unbound));
        }

        Set<String> contrast = contrastColumns(raw);
        String measure = raw.measure().column();

        LinkedHashSet<String> exclude = new LinkedHashSet<>();
        if (raw.decomposition() != null && raw.decomposition().exclude() != null) {
            for (String c : raw.decomposition().exclude()) {
                if (!blank(c)) {
                    exclude.add(c);
                }
            }
        }
        // Always exclude measure + every contrast-defining column.
        exclude.add(measure);
        for (String c : contrast) {
            exclude.add(c);
        }

        List<String> dimensions = new ArrayList<>();
        if (raw.decomposition() != null && raw.decomposition().dimensions() != null) {
            for (String d : raw.decomposition().dimensions()) {
                if (blank(d)) {
                    continue;
                }
                if (equalsIgnore(d, measure)) {
                    continue;
                }
                if (containsIgnore(contrast, d)) {
                    continue;
                }
                if (containsIgnore(exclude, d)) {
                    continue;
                }
                if (!containsIgnore(dimensions, d)) {
                    dimensions.add(d);
                }
            }
        }

        InvestigationObjective.Scope scope = raw.scope() != null
                ? raw.scope()
                : new InvestigationObjective.Scope(List.of());

        InvestigationObjective.Decomposition decomposition =
                new InvestigationObjective.Decomposition(
                        List.copyOf(dimensions),
                        List.copyOf(exclude));

        InvestigationObjective normalized = new InvestigationObjective(
                raw.measure(),
                scope,
                raw.comparison(),
                decomposition,
                blank(raw.salience())
                        ? InvestigationObjective.SALIENCE_DIFFERENCE
                        : raw.salience(),
                raw.semanticConfidence());

        return NormalizationResult.ok(normalized);
    }

    public static Set<String> knownColumns(ApprovedCatalogueSnapshot catalogue) {
        Set<String> out = new LinkedHashSet<>();
        if (catalogue == null || catalogue.columns() == null) {
            return out;
        }
        for (ApprovedCatalogueSnapshot.CatalogueColumn c : catalogue.columns()) {
            if (c != null && !blank(c.columnName())) {
                out.add(c.columnName());
            }
        }
        return out;
    }

    public static Set<String> knownColumns(RegistryResolutionBundle bundle) {
        Set<String> out = new LinkedHashSet<>();
        if (bundle == null) {
            return out;
        }
        if (bundle.metrics() != null) {
            for (MetricDescriptor m : bundle.metrics()) {
                String col = CatalogueTimeColumnResolver.bareColumn(
                        m.expressionTemplate() != null ? m.expressionTemplate() : m.key());
                if (!blank(col)) {
                    out.add(col);
                }
            }
        }
        if (bundle.dimensions() != null) {
            for (DimensionDescriptor d : bundle.dimensions()) {
                String col = CatalogueTimeColumnResolver.columnName(d);
                if (!blank(col)) {
                    out.add(col);
                }
            }
        }
        return out;
    }

    private Predicate<String> columnExistsPredicate(ApprovedCatalogueSnapshot catalogue) {
        Set<String> known = knownColumns(catalogue);
        return col -> containsIgnore(known, col);
    }

    private Predicate<String> columnExistsPredicate(RegistryResolutionBundle bundle) {
        Set<String> known = knownColumns(bundle);
        return col -> containsIgnore(known, col);
    }

    private List<String> unboundColumns(InvestigationObjective o, Predicate<String> exists) {
        List<String> bad = new ArrayList<>();
        collectIfUnbound(o.measure().column(), exists, bad);
        if (o.measure().grain() != null) {
            for (String g : o.measure().grain()) {
                collectIfUnbound(g, exists, bad);
            }
        }
        if (o.scope() != null && o.scope().filters() != null) {
            for (InvestigationObjective.Filter f : o.scope().filters()) {
                collectIfUnbound(f.column(), exists, bad);
            }
        }
        collectSliceUnbound(o.comparison().focal(), exists, bad);
        collectSliceUnbound(o.comparison().baseline(), exists, bad);
        if (o.decomposition() != null && o.decomposition().dimensions() != null) {
            for (String d : o.decomposition().dimensions()) {
                collectIfUnbound(d, exists, bad);
            }
        }
        return bad;
    }

    private void collectSliceUnbound(InvestigationObjective.Slice slice,
                                     Predicate<String> exists,
                                     List<String> bad) {
        if (slice == null || slice.constraints() == null) {
            return;
        }
        for (InvestigationObjective.Constraint c : slice.constraints()) {
            if (c == null) {
                continue;
            }
            if (c.filter() != null) {
                collectIfUnbound(c.filter().column(), exists, bad);
            }
            if (c.timeRange() != null) {
                collectIfUnbound(c.timeRange().column(), exists, bad);
            }
            if (c.rank() != null) {
                collectIfUnbound(c.rank().column(), exists, bad);
                if (c.rank().grain() != null) {
                    for (String g : c.rank().grain()) {
                        collectIfUnbound(g, exists, bad);
                    }
                }
            }
        }
    }

    private void collectIfUnbound(String column, Predicate<String> exists, List<String> bad) {
        if (blank(column)) {
            return;
        }
        if (!exists.test(column) && !containsIgnore(bad, column)) {
            bad.add(column);
        }
    }

    private Set<String> contrastColumns(InvestigationObjective o) {
        Set<String> cols = new LinkedHashSet<>();
        addSliceColumns(o.comparison().focal(), cols);
        addSliceColumns(o.comparison().baseline(), cols);
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
            if (c.filter() != null && !blank(c.filter().column())) {
                cols.add(c.filter().column());
            }
            if (c.timeRange() != null && !blank(c.timeRange().column())) {
                cols.add(c.timeRange().column());
            }
            if (c.rank() != null) {
                if (!blank(c.rank().column())) {
                    cols.add(c.rank().column());
                }
                if (c.rank().grain() != null) {
                    for (String g : c.rank().grain()) {
                        if (!blank(g)) {
                            cols.add(g);
                        }
                    }
                }
            }
        }
    }

    private static boolean slicesEqual(InvestigationObjective.Slice a, InvestigationObjective.Slice b) {
        List<InvestigationObjective.Constraint> ac = a.constraints() == null ? List.of() : a.constraints();
        List<InvestigationObjective.Constraint> bc = b.constraints() == null ? List.of() : b.constraints();
        return ac.equals(bc);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean equalsIgnore(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static boolean containsIgnore(Iterable<String> values, String candidate) {
        if (candidate == null) {
            return false;
        }
        for (String v : values) {
            if (v != null && v.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    public record NormalizationResult(
            boolean ok,
            InvestigationObjective objective,
            String reason
    ) {
        public static NormalizationResult ok(InvestigationObjective objective) {
            return new NormalizationResult(true, objective, null);
        }

        public static NormalizationResult reject(String reason) {
            return new NormalizationResult(false, null, reason);
        }
    }
}
