package com.example.BACKEND.catalogue.decision.execution.materialization;

import com.example.BACKEND.catalogue.decision.execution.framework.ColumnProfile;
import com.example.BACKEND.catalogue.decision.execution.framework.SchemaProfile;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializationSpec.SpecType;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.grounding.PresentationLabelResolver;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds the ordered list of {@link MaterializationSpec}s to execute.
 *
 * Construction is driven by TWO inputs:
 *   1. The {@link InvestigationPlan} — what the analytical intent requires
 *   2. The {@link SchemaProfile} + detected temporal columns — what the data has
 *
 * Priority rules (lower number = higher priority):
 *   - Temporal intents (TREND, CONTRIBUTION with temporal focus) → time-derived
 *     specs get priority 0; entity specs get priority 2
 *   - Entity/ranking intents → entity dimension specs get priority 0; time gets 3
 *   - Any intent → composite entity (A→B) spec priority 1
 *   - All other dimensions always included at lower priority
 *
 * This ensures the materializer always executes the right grouping first for the
 * question being asked, without hardcoding domain knowledge.
 */
@Component
public class MaterializationPlanBuilder {

    private final PresentationLabelResolver labels;

    public MaterializationPlanBuilder(PresentationLabelResolver labels) {
        this.labels = labels;
    }

    // Temporal derivation suffixes and their priority boost for temporal intents
    private static final List<TemporalDerivation> TEMPORAL_DERIVATIONS = List.of(
            new TemporalDerivation("_hour_of_day", "Hour of Day",   0),
            new TemporalDerivation("_weekday",     "Day of Week",   1),
            new TemporalDerivation("_month",       "Month",         2),
            new TemporalDerivation("_quarter",     "Quarter",       3)
    );

    public List<MaterializationSpec> build(
            InvestigationPlan         plan,
            SchemaProfile             profile,
            List<String>              detectedTemporalColumns
    ) {
        AnalyticalIntentType intent = plan != null ? plan.intentType()
                                                   : AnalyticalIntentType.GENERAL_ANALYSIS;
        boolean isTemporalFirst = isTemporalFirst(intent, plan);

        List<MaterializationSpec> specs = new ArrayList<>();

        // ── Plan-requested grouping (highest priority) ────────────────────
        if (plan != null && plan.reasoningPlan() != null
                && plan.reasoningPlan().metricBinding() != null) {
            String groupingCol = plan.reasoningPlan().metricBinding().groupingColumn();
            if (groupingCol != null && !groupingCol.isBlank()) {
                specs.add(new MaterializationSpec(
                        groupingCol,
                        groupingCol,
                        plan.reasoningPlan().metricBinding().groupingLabel() != null
                                ? plan.reasoningPlan().metricBinding().groupingLabel()
                                : toLabel(groupingCol),
                        groupingCol.toLowerCase(Locale.ROOT).endsWith("_bucket")
                                ? SpecType.SOURCE_DIMENSION
                                : SpecType.SOURCE_DIMENSION,
                        -1
                ));
            }
        }

        // ── Derived temporal specs ────────────────────────────────────────
        for (String sourceCol : detectedTemporalColumns) {
            for (TemporalDerivation deriv : TEMPORAL_DERIVATIONS) {
                String derivedKey    = "_derived_" + sourceCol + deriv.suffix();
                int    basePriority  = isTemporalFirst ? deriv.order() : (deriv.order() + 10);
                specs.add(new MaterializationSpec(
                        derivedKey,
                        sourceCol,
                        deriv.label(),
                        SpecType.DERIVED_TIME,
                        basePriority
                ));
            }
        }

        // ── Source dimension specs (from SchemaProfile) ───────────────────
        List<ColumnProfile> dims = profile.dimensions().stream()
                .sorted(Comparator.comparingInt(ColumnProfile::cardinality))
                .limit(4)
                .collect(Collectors.toList());

        for (int i = 0; i < dims.size(); i++) {
            ColumnProfile dim = dims.get(i);
            int priority = isTemporalFirst ? (i + 5) : i;
            specs.add(new MaterializationSpec(
                    dim.columnName(),
                    dim.columnName(),
                    toLabel(dim.columnName()),
                    SpecType.SOURCE_DIMENSION,
                    priority
            ));
        }

        // ── Composite entity spec (top 2 dimensions combined) ─────────────
        if (dims.size() >= 2) {
            ColumnProfile a = dims.get(0);
            ColumnProfile b = dims.get(1);
            int priority = isTemporalFirst ? 8 : 2;
            specs.add(new MaterializationSpec(
                    "_composite_" + a.columnName() + "__" + b.columnName(),
                    a.columnName() + "," + b.columnName(),
                    toLabel(a.columnName()) + " → " + toLabel(b.columnName()),
                    SpecType.COMPOSITE,
                    priority
            ));
        }

        return specs.stream()
                .sorted(Comparator.comparingInt(MaterializationSpec::priority))
                .collect(Collectors.toList());
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    /** Returns true when the intent/plan primarily demands temporal grouping. */
    private boolean isTemporalFirst(AnalyticalIntentType intent, InvestigationPlan plan) {
        if (intent == AnalyticalIntentType.TREND_ANALYSIS) return true;
        if (intent == AnalyticalIntentType.FORECASTING)    return true;
        // Check dimensional focus for temporal keywords
        if (plan != null && plan.dimensionalFocus() != null) {
            return plan.dimensionalFocus().stream().anyMatch(f ->
                    f != null && (f.toLowerCase().contains("time")
                            || f.toLowerCase().contains("hour")
                            || f.toLowerCase().contains("day")
                            || f.toLowerCase().contains("week")
                            || f.toLowerCase().contains("month")
                            || f.toLowerCase().contains("period")
                            || f.toLowerCase().contains("trend")));
        }
        return false;
    }

    private String toLabel(String columnName) {
        return labels.resolveDimension(columnName);
    }

    private record TemporalDerivation(String suffix, String label, int order) {}
}
