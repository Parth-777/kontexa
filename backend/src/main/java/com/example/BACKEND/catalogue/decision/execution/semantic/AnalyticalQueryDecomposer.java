package com.example.BACKEND.catalogue.decision.execution.semantic;

import com.example.BACKEND.catalogue.decision.execution.semantic.AnalyticalDecomposition.*;
import com.example.BACKEND.catalogue.decision.execution.semantic.AnalyticalDecomposition.TemporalSpec.Granularity;
import com.example.BACKEND.catalogue.decision.execution.semantic.SchemaSemanticResolver.ResolvedColumn;
import com.example.BACKEND.catalogue.decision.execution.semantic.SchemaSemanticResolver.ResolvedSchema;
import com.example.BACKEND.catalogue.decision.execution.semantic.SchemaSemanticResolver.SemanticRole;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Translates a structured {@link InvestigationPlan} + {@link ResolvedSchema}
 * into a typed {@link AnalyticalDecomposition}.
 *
 * This is the bridge between "what the plan says to investigate" and
 * "which columns to GROUP BY, aggregate, rank, and derive".
 *
 * NEVER receives raw NLP text.  Operates only on the classified schema and
 * the intent/dimensional-focus from the already-computed investigation plan.
 *
 * Critical rules:
 *   - Temporal intent → always include time dimension grouping if any TIME_DIMENSION exists
 *   - Ranking/contribution intent → prefer ENTITY_DIMENSION grouping
 *   - Efficiency intent → require both VALUE_METRIC and VOLUME_METRIC; derive ratio
 *   - All intents → always include primary VALUE_METRIC in aggregates
 */
@Component
public class AnalyticalQueryDecomposer {

    private static final int DEFAULT_LIMIT = 20;

    public AnalyticalDecomposition decompose(InvestigationPlan plan, ResolvedSchema schema) {
        AnalyticalIntentType intent = plan.intentType();
        boolean wantsTemporal      = isTemporalIntent(plan);
        boolean wantsEfficiency    = isEfficiencyIntent(plan);
        AnalyticalIntentType canonical = intent.canonical();
        boolean wantsContribution  = canonical == AnalyticalIntentType.CONTRIBUTION
                || canonical == AnalyticalIntentType.COMPOSITION
                || canonical == AnalyticalIntentType.DISTRIBUTION;

        // ── Grouping dimensions ───────────────────────────────────────────
        List<DimensionTarget> groupingDims = buildGroupingDimensions(
                schema, wantsTemporal, intent);

        // ── Metrics ───────────────────────────────────────────────────────
        List<MetricTarget> metrics = buildMetricTargets(schema);

        // ── Derived metrics (efficiency ratios) ───────────────────────────
        List<DerivedMetricTarget> derived = wantsEfficiency
                ? buildDerivedMetrics(metrics) : List.of();

        // ── Temporal spec ─────────────────────────────────────────────────
        TemporalSpec temporalSpec = buildTemporalSpec(schema, wantsTemporal);

        // ── Ranking ───────────────────────────────────────────────────────
        RankingSpec rankingSpec = buildRankingSpec(intent, metrics, derived, wantsEfficiency);

        return new AnalyticalDecomposition(
                intent,
                schema.tableRef(),
                groupingDims,
                metrics,
                derived,
                temporalSpec,
                rankingSpec,
                wantsContribution
        );
    }

    // ─── grouping ────────────────────────────────────────────────────────

    private List<DimensionTarget> buildGroupingDimensions(
            ResolvedSchema schema, boolean wantsTemporal, AnalyticalIntentType intent
    ) {
        List<DimensionTarget> dims = new ArrayList<>();

        // Temporal grouping (primary for trend/temporal intents)
        if (wantsTemporal || intent == AnalyticalIntentType.TREND_ANALYSIS
                || intent == AnalyticalIntentType.FORECASTING) {
            schema.firstByRole(SemanticRole.TIME_DIMENSION).ifPresent(tc -> {
                // For trend: use monthly/weekly bucketing
                // For all temporal: add hourly bucket
                if (intent == AnalyticalIntentType.TREND_ANALYSIS
                        || intent == AnalyticalIntentType.FORECASTING) {
                    dims.add(monthBucket(tc));
                } else {
                    dims.add(hourBucket(tc));
                    dims.add(weekdayBucket(tc));
                }
            });
        }

        // Entity dimension grouping
        schema.byRole(SemanticRole.ENTITY_DIMENSION).stream()
                .limit(2)
                .forEach(ec -> dims.add(new DimensionTarget(
                        ec.columnName(), "ENTITY_DIMENSION",
                        false, ec.columnName(),
                        toLabel(ec.columnName())
                )));

        // If no entity dimension found, try composite (two lowest-cardinality columns)
        if (dims.stream().noneMatch(d -> "ENTITY_DIMENSION".equals(d.semanticRole()))) {
            schema.byRole(SemanticRole.UNKNOWN).stream().limit(1)
                    .forEach(u -> dims.add(new DimensionTarget(
                            u.columnName(), "GENERIC_DIMENSION",
                            false, u.columnName(), toLabel(u.columnName())
                    )));
        }

        return dims;
    }

    // ─── metrics ─────────────────────────────────────────────────────────

    private List<MetricTarget> buildMetricTargets(ResolvedSchema schema) {
        List<MetricTarget> targets = new ArrayList<>();
        int i = 0;
        for (ResolvedColumn col : schema.byRole(SemanticRole.VALUE_METRIC)) {
            targets.add(new MetricTarget(col.columnName(), "SUM",
                    "val_" + i++, "VALUE_METRIC"));
        }
        i = 0;
        for (ResolvedColumn col : schema.byRole(SemanticRole.VOLUME_METRIC)) {
            targets.add(new MetricTarget(col.columnName(), "SUM",
                    "vol_" + i++, "VOLUME_METRIC"));
        }
        i = 0;
        for (ResolvedColumn col : schema.byRole(SemanticRole.RATE_METRIC)) {
            targets.add(new MetricTarget(col.columnName(), "AVG",
                    "rate_" + i++, "RATE_METRIC"));
        }
        // Fallback: if nothing found, add COUNT(*)
        if (targets.isEmpty()) {
            targets.add(new MetricTarget("*", "COUNT", "row_count", "VOLUME_METRIC"));
        }
        return targets;
    }

    // ─── derived metrics ──────────────────────────────────────────────────

    private List<DerivedMetricTarget> buildDerivedMetrics(List<MetricTarget> metrics) {
        List<DerivedMetricTarget> derived = new ArrayList<>();
        Optional<MetricTarget> valOpt = metrics.stream()
                .filter(m -> "VALUE_METRIC".equals(m.semanticRole())).findFirst();
        Optional<MetricTarget> volOpt = metrics.stream()
                .filter(m -> "VOLUME_METRIC".equals(m.semanticRole())).findFirst();
        if (valOpt.isPresent() && volOpt.isPresent()) {
            derived.add(new DerivedMetricTarget(
                    valOpt.get().alias(),
                    volOpt.get().alias(),
                    "efficiency_ratio"
            ));
        }
        return derived;
    }

    // ─── temporal ────────────────────────────────────────────────────────

    private TemporalSpec buildTemporalSpec(ResolvedSchema schema, boolean wantsTemporal) {
        Optional<ResolvedColumn> tc = schema.firstByRole(SemanticRole.TIME_DIMENSION);
        if (tc.isEmpty()) {
            return new TemporalSpec(null, List.of(), false);
        }
        List<Granularity> granularities = new ArrayList<>();
        if (wantsTemporal) {
            granularities.add(Granularity.HOUR_OF_DAY);
            granularities.add(Granularity.WEEKDAY);
        }
        granularities.add(Granularity.MONTH);
        return new TemporalSpec(tc.get().columnName(), granularities, true);
    }

    // ─── ranking ─────────────────────────────────────────────────────────

    private RankingSpec buildRankingSpec(
            AnalyticalIntentType intent,
            List<MetricTarget>   metrics,
            List<DerivedMetricTarget> derived,
            boolean wantsEfficiency
    ) {
        // Prefer efficiency ratio for efficiency queries
        if (wantsEfficiency && !derived.isEmpty()) {
            return new RankingSpec(derived.get(0).outputAlias(), true, DEFAULT_LIMIT);
        }
        // Prefer primary value metric
        Optional<MetricTarget> val = metrics.stream()
                .filter(m -> "VALUE_METRIC".equals(m.semanticRole())).findFirst();
        if (val.isPresent()) {
            return new RankingSpec(val.get().alias(), true, DEFAULT_LIMIT);
        }
        // Fallback: first metric
        if (!metrics.isEmpty()) {
            return new RankingSpec(metrics.get(0).alias(), true, DEFAULT_LIMIT);
        }
        return new RankingSpec("row_count", true, DEFAULT_LIMIT);
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private boolean isTemporalIntent(InvestigationPlan plan) {
        if (plan.intentType() == AnalyticalIntentType.TREND_ANALYSIS
                || plan.intentType() == AnalyticalIntentType.FORECASTING) return true;
        if (plan.dimensionalFocus() == null) return false;
        return plan.dimensionalFocus().stream().anyMatch(f -> f != null &&
                (f.toLowerCase().contains("time")
                || f.toLowerCase().contains("hour")
                || f.toLowerCase().contains("day")
                || f.toLowerCase().contains("week")
                || f.toLowerCase().contains("month")
                || f.toLowerCase().contains("period")
                || f.toLowerCase().contains("trend")));
    }

    private boolean isEfficiencyIntent(InvestigationPlan plan) {
        if (plan.intentType() == AnalyticalIntentType.EFFICIENCY) return true;
        if (plan.intentType() == AnalyticalIntentType.RANKING
                || plan.intentType() == AnalyticalIntentType.STRATEGIC_PRIORITIZATION) {
            return plan.dimensionalFocus() != null &&
                    plan.dimensionalFocus().stream().anyMatch(f -> f != null &&
                            (f.toLowerCase().contains("efficiency")
                            || f.toLowerCase().contains("per ")
                            || f.toLowerCase().contains("ratio")));
        }
        return false;
    }

    private DimensionTarget hourBucket(ResolvedColumn tc) {
        return new DimensionTarget(tc.columnName(), "TIME_DIMENSION",
                true, "EXTRACT(HOUR FROM " + tc.columnName() + ")",
                "Hour of Day");
    }

    private DimensionTarget weekdayBucket(ResolvedColumn tc) {
        return new DimensionTarget(tc.columnName(), "TIME_DIMENSION",
                true, "EXTRACT(DOW FROM " + tc.columnName() + ")",
                "Day of Week");
    }

    private DimensionTarget monthBucket(ResolvedColumn tc) {
        return new DimensionTarget(tc.columnName(), "TIME_DIMENSION",
                true, "DATE_TRUNC('month', " + tc.columnName() + ")",
                "Month");
    }

    private String toLabel(String col) {
        return Arrays.stream(col.split("[_\\-]"))
                .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }
}
