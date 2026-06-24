package com.example.BACKEND.catalogue.decision.execution.semantic;

import com.example.BACKEND.catalogue.decision.execution.semantic.AnalyticalDecomposition.*;
import com.example.BACKEND.catalogue.decision.execution.semantic.AnalyticalExecutionPlan.*;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Compiles an {@link AnalyticalDecomposition} into an {@link AnalyticalExecutionPlan}.
 *
 * Produces one {@link ComputationStep} per distinct grouping strategy:
 *   - temporal bucket steps (one per granularity that exists in the schema)
 *   - entity dimension step (single GROUP BY categorical column)
 *   - composite entity step (two dimensions combined)
 *   - efficiency step (value/volume ratio ranking)
 *   - contribution step (with window-function share_pct)
 *   - general summary fallback (if nothing else was produced)
 *
 * The result is an ordered list of ComputationSteps that the {@link AnalyticalSQLGenerator}
 * converts to concrete SQL.  Each step is independent — the warehouse executor can
 * run them in parallel or in sequence.
 */
@Component
public class AnalyticalPlanCompiler {

    public AnalyticalExecutionPlan compile(AnalyticalDecomposition decomp) {
        String planId    = "aep-" + UUID.randomUUID().toString().substring(0, 8);
        String tableRef  = decomp.tableRef();
        List<ComputationStep> steps = new ArrayList<>();

        // ── Temporal steps ────────────────────────────────────────────────
        // For each temporal grouping dimension, produce a ranked aggregation step
        decomp.groupingDimensions().stream()
                .filter(DimensionTarget::derived)
                .filter(d -> "TIME_DIMENSION".equals(d.semanticRole()))
                .forEach(td -> steps.add(buildTemporalStep(td, decomp, tableRef)));

        // ── Entity dimension steps ────────────────────────────────────────
        List<DimensionTarget> entityDims = decomp.groupingDimensions().stream()
                .filter(d -> !d.derived())
                .collect(Collectors.toList());

        // Single entity dimension ranking
        entityDims.stream().limit(1).forEach(ed -> {
            StepType st = decomp.requiresConcentration()
                    ? StepType.CONTRIBUTION_ANALYSIS
                    : StepType.ENTITY_GROUPED_RANKING;
            steps.add(buildEntityStep(ed, decomp, tableRef, st));
        });

        // Composite entity (second dimension cross)
        if (entityDims.size() >= 2) {
            steps.add(buildCompositeStep(entityDims.get(0), entityDims.get(1), decomp, tableRef));
        }

        // ── Efficiency step ───────────────────────────────────────────────
        if (!decomp.derivedMetrics().isEmpty() && !entityDims.isEmpty()) {
            steps.add(buildEfficiencyStep(entityDims.get(0), decomp, tableRef));
        }

        // ── Trend time-series (chronological, no ranking) ─────────────────
        if (decomp.intentType() == AnalyticalIntentType.TREND_ANALYSIS
                || decomp.intentType() == AnalyticalIntentType.FORECASTING) {
            decomp.groupingDimensions().stream()
                    .filter(DimensionTarget::derived)
                    .limit(1)
                    .forEach(td -> steps.add(buildTrendStep(td, decomp, tableRef)));
        }

        // ── Fallback: general aggregate ───────────────────────────────────
        if (steps.isEmpty()) {
            steps.add(buildGeneralFallback(decomp, tableRef));
        }

        return new AnalyticalExecutionPlan(planId, tableRef, steps);
    }

    // ─── step builders ────────────────────────────────────────────────────

    private ComputationStep buildTemporalStep(
            DimensionTarget td, AnalyticalDecomposition decomp, String table
    ) {
        String expr     = td.derivationExpr();
        String alias    = "time_bucket";
        List<String> groupBy = List.of(expr);

        List<String> select = new ArrayList<>();
        select.add(expr + " AS " + alias);
        addAggregateSelects(select, decomp.metrics());

        String orderBy = decomp.rankingSpec().orderByAlias() + " "
                + (decomp.rankingSpec().descending() ? "DESC" : "ASC");

        return new ComputationStep(
                "temporal_" + slugify(td.displayLabel()),
                StepType.TEMPORAL_GROUPED_RANKING,
                table, groupBy, select, orderBy,
                decomp.rankingSpec().limit(),
                "Group by " + td.displayLabel() + " and rank by " + decomp.rankingSpec().orderByAlias()
        );
    }

    private ComputationStep buildEntityStep(
            DimensionTarget ed, AnalyticalDecomposition decomp,
            String table, StepType stepType
    ) {
        List<String> groupBy = List.of(ed.columnName());
        List<String> select  = new ArrayList<>();
        select.add(ed.columnName());
        addAggregateSelects(select, decomp.metrics());

        // Share-pct for contribution analysis (window function)
        if (stepType == StepType.CONTRIBUTION_ANALYSIS) {
            String valAlias = decomp.metrics().stream()
                    .filter(m -> "VALUE_METRIC".equals(m.semanticRole()))
                    .map(MetricTarget::alias)
                    .findFirst().orElse("val_0");
            select.add("ROUND(100.0 * " + valAlias + " / NULLIF(SUM(" + valAlias + ") OVER(), 0), 2) AS share_pct");
        }

        String orderBy = decomp.rankingSpec().orderByAlias() + " "
                + (decomp.rankingSpec().descending() ? "DESC" : "ASC");

        return new ComputationStep(
                "entity_" + slugify(ed.columnName()),
                stepType, table, groupBy, select, orderBy,
                decomp.rankingSpec().limit(),
                "Group by " + ed.displayLabel() + " and rank"
        );
    }

    private ComputationStep buildCompositeStep(
            DimensionTarget d1, DimensionTarget d2,
            AnalyticalDecomposition decomp, String table
    ) {
        List<String> groupBy = List.of(d1.columnName(), d2.columnName());
        List<String> select  = new ArrayList<>();
        select.add(d1.columnName());
        select.add(d2.columnName());
        addAggregateSelects(select, decomp.metrics());

        String orderBy = decomp.rankingSpec().orderByAlias() + " DESC";
        return new ComputationStep(
                "composite_" + slugify(d1.columnName()) + "_" + slugify(d2.columnName()),
                StepType.COMPOSITE_ENTITY_RANKING, table, groupBy, select,
                orderBy, decomp.rankingSpec().limit(),
                "Composite grouping: " + d1.displayLabel() + " → " + d2.displayLabel()
        );
    }

    private ComputationStep buildEfficiencyStep(
            DimensionTarget ed, AnalyticalDecomposition decomp, String table
    ) {
        DerivedMetricTarget dm = decomp.derivedMetrics().get(0);
        List<String> groupBy = List.of(ed.columnName());
        List<String> select  = new ArrayList<>();
        select.add(ed.columnName());
        addAggregateSelects(select, decomp.metrics());
        select.add("ROUND(CAST(" + dm.numeratorAlias() + " AS FLOAT) / NULLIF(" + dm.denominatorAlias() + ", 0), 4) AS " + dm.outputAlias());

        String orderBy = dm.outputAlias() + " DESC";
        return new ComputationStep(
                "efficiency_" + slugify(ed.columnName()),
                StepType.EFFICIENCY_RANKING, table, groupBy, select,
                orderBy, decomp.rankingSpec().limit(),
                "Efficiency ratio: " + dm.outputAlias() + " ranked by " + ed.displayLabel()
        );
    }

    private ComputationStep buildTrendStep(
            DimensionTarget td, AnalyticalDecomposition decomp, String table
    ) {
        List<String> groupBy = List.of(td.derivationExpr());
        List<String> select  = new ArrayList<>();
        select.add(td.derivationExpr() + " AS time_period");
        addAggregateSelects(select, decomp.metrics());

        // Chronological order for trend
        String orderBy = "time_period ASC";
        return new ComputationStep(
                "trend_" + slugify(td.displayLabel()),
                StepType.TREND_TIMESERIES, table, groupBy, select,
                orderBy, 0,   // no limit for trend
                "Trend over time: " + td.displayLabel()
        );
    }

    private ComputationStep buildGeneralFallback(AnalyticalDecomposition decomp, String table) {
        List<String> select = new ArrayList<>();
        addAggregateSelects(select, decomp.metrics());
        return new ComputationStep(
                "general_summary",
                StepType.GENERAL_SUMMARY, table, List.of(), select,
                null, 0,
                "General aggregation summary"
        );
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private void addAggregateSelects(List<String> select, List<MetricTarget> metrics) {
        for (MetricTarget m : metrics) {
            if ("*".equals(m.columnName())) {
                select.add("COUNT(*) AS " + m.alias());
            } else {
                select.add(m.aggregation() + "(" + m.columnName() + ") AS " + m.alias());
            }
        }
    }

    private String slugify(String s) {
        return s == null ? "col" : s.toLowerCase().replaceAll("[^a-z0-9]", "_");
    }
}
