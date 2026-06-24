package com.example.BACKEND.catalogue.decision.execution.materialization;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.StructuralFinding;
import com.example.BACKEND.catalogue.decision.execution.framework.SchemaProfile;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializationSpec.SpecType;
import com.example.BACKEND.catalogue.decision.execution.materialization.GroupedWarehouseResultDetector;
import com.example.BACKEND.catalogue.decision.execution.materialization.AnalyticalWarehouseResultDetector.CorrelationShape;
import com.example.BACKEND.catalogue.decision.execution.materialization.AnalyticalWarehouseResultDetector.ScalarShape;
import com.example.BACKEND.catalogue.decision.execution.materialization.GroupedWarehouseResultDetector.GroupedShape;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedCorrelation;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedScalar;
import com.example.BACKEND.catalogue.decision.grounding.PresentationLabelResolver;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Bridges analytical plan → executable grouped computations → structured evidence.
 *
 * Pipeline for every request:
 *   1. Detect temporal columns by sampling values (brute-force, independent of names)
 *   2. Materialize derived dimensions (hour_of_day, weekday, month, quarter) into rows
 *   3. Build materialization specs ordered by plan intent
 *   4. Execute GROUP BY for each spec using {@link GroupByExecutor}
 *   5. Compute composite entity groups (A→B) when two dimensions exist
 *   6. Select primary grouping by analytical signal strength
 *   7. Generate quantified structural findings
 *
 * Output: {@link MaterializedQueryResult} — pre-computed, ranked evidence ready for synthesis.
 *
 * This layer is completely dataset-agnostic.  It operates on whatever rows the
 * warehouse returned and discovers grouping opportunities entirely from data.
 */
@Service
public class AnalyticalQueryMaterializer {

    private static final Logger log = LoggerFactory.getLogger(AnalyticalQueryMaterializer.class);

    private final DerivedDimensionMaterializer  derivedMaterializer;
    private final NumericDimensionBucketer      numericBucketer;
    private final MaterializationPlanBuilder    planBuilder;
    private final GroupByExecutor               groupByExecutor;
    private final PresentationLabelResolver   labels;

    public AnalyticalQueryMaterializer(
            DerivedDimensionMaterializer  derivedMaterializer,
            NumericDimensionBucketer      numericBucketer,
            MaterializationPlanBuilder    planBuilder,
            GroupByExecutor               groupByExecutor,
            PresentationLabelResolver     labels
    ) {
        this.derivedMaterializer = derivedMaterializer;
        this.numericBucketer     = numericBucketer;
        this.planBuilder         = planBuilder;
        this.groupByExecutor     = groupByExecutor;
        this.labels              = labels;
    }

    public MaterializedQueryResult materialize(
            List<Map<String, Object>> rows,
            SchemaProfile             profile,
            InvestigationPlan         plan
    ) {
        if (rows == null || rows.isEmpty() || profile == null)
            return MaterializedQueryResult.empty();

        log.info("[materializer] raw warehouse rows={} columns={}",
                rows.size(), rows.getFirst().keySet());

        Optional<CorrelationShape> correlationShape = AnalyticalWarehouseResultDetector.detectCorrelation(rows);
        if (correlationShape.isPresent()) {
            MaterializedQueryResult correlation = materializeCorrelation(
                    correlationShape.get(), rows, plan);
            if (correlation.hasContent()) {
                log.info("[materializer] correlation result: r={} n={}",
                        correlation.correlation().correlationCoefficient(),
                        correlation.correlation().sampleSize());
                return correlation;
            }
        }

        Optional<ScalarShape> scalarShape = AnalyticalWarehouseResultDetector.detectScalar(rows);
        if (scalarShape.isPresent()) {
            MaterializedQueryResult scalar = materializeScalar(scalarShape.get(), rows, plan);
            if (scalar.hasContent()) {
                log.info("[materializer] scalar result: {}={}",
                        scalar.scalar().metricColumn(), scalar.scalar().value());
                return scalar;
            }
        }

        GroupedShape grouped = GroupedWarehouseResultDetector.detect(rows);
        if (grouped != null) {
            log.info("[materializer] pre-aggregated shape detected: {}", grouped.reason());
            MaterializedQueryResult preAgg = materializePreAggregated(rows, grouped, plan);
            if (preAgg.hasContent()) {
                log.info("[materializer] pre-aggregated materialized groups={} sample={}",
                        preAgg.primaryGrouping().groupCount(),
                        preAgg.primaryGrouping().top(3).stream()
                                .map(e -> e.entityKey() + "=" + e.totalValue())
                                .collect(Collectors.joining(", ")));
                return preAgg;
            }
            log.warn("[materializer] pre-aggregated passthrough produced no groups");
        }

        if (!profile.hasValues()) {
            log.warn("[materializer] schema profile has no value columns");
            return MaterializedQueryResult.empty();
        }

        String primaryValueCol = profile.primaryValue() != null
                ? profile.primaryValue().columnName() : null;
        if (primaryValueCol == null) {
            log.warn("[materializer] no primary value column in profile columns={}",
                    profile.columns().stream().map(c -> c.columnName() + ":" + c.role())
                            .collect(Collectors.joining(", ")));
            return MaterializedQueryResult.empty();
        }

        String volumeCol = profile.primaryVolume() != null
                ? profile.primaryVolume().columnName() : null;

        String valueLabel = labels.resolveMetric(primaryValueCol);

        // ── Step 1: Detect temporal columns ──────────────────────────────
        List<String> temporalCols = derivedMaterializer.detectTemporalColumns(rows);
        log.info("[materializer] detected temporal columns: {}", temporalCols);

        // ── Step 2: Add derived temporal dimensions to rows ───────────────
        List<Map<String, Object>> enrichedRows =
                temporalCols.isEmpty() ? rows
                        : derivedMaterializer.materializeTemporalDimensions(rows, temporalCols);

        // ── Step 3: Build materialization plan ────────────────────────────
        List<MaterializationSpec> specs = planBuilder.build(plan, profile, temporalCols);
        log.info("[materializer] specs planned: {}", specs.stream()
                .map(s -> s.groupingKey() + "(" + s.specType() + ",p=" + s.priority() + ")")
                .collect(Collectors.joining(", ")));

        // ── Step 3b: Derive numeric bucket columns when plan requests *_bucket ─
        List<Map<String, Object>> bucketedRows =
                numericBucketer.materializeBucketColumns(enrichedRows, specs);

        // ── Step 4+5: Execute GROUP BY for all specs ──────────────────────
        List<MaterializedGrouping> groupings = new ArrayList<>();
        for (MaterializationSpec spec : specs) {
            if (spec.specType() == SpecType.COMPOSITE) {
                // Composite: group by combined dimension key derived below
                String[] parts = spec.sourceColumn().split(",");
                if (parts.length == 2) {
                    List<Map<String, Object>> compositeRows = buildCompositeRows(bucketedRows, parts[0].trim(), parts[1].trim(), spec.groupingKey());
                    MaterializedGrouping g = groupByExecutor.execute(compositeRows, spec, primaryValueCol, volumeCol);
                    if (g.hasData()) groupings.add(g);
                }
                continue;
            }
            MaterializedGrouping g = groupByExecutor.execute(bucketedRows, spec, primaryValueCol, volumeCol);
            if (g.hasData()) groupings.add(g);
        }

        if (groupings.isEmpty()) {
            log.warn("[materializer] no groupings produced sufficient data; specs={} valueCol={}",
                    specs.size(), primaryValueCol);
            return MaterializedQueryResult.empty();
        }

        // ── Step 6: Select primary grouping (highest signal score) ────────
        MaterializedGrouping primary = selectPrimary(groupings, plan);

        // ── Step 7: Generate structural findings ──────────────────────────
        List<StructuralFinding> findings = generateFindings(groupings, primary, valueLabel);

        log.info("[materializer] primary={} groups={} findings={}",
                primary.spec().displayLabel(), primary.groupCount(), findings.size());

        return MaterializedQueryResult.grouped(groupings, primary, findings, valueLabel, rows.size());
    }

    private MaterializedQueryResult materializeCorrelation(
            CorrelationShape shape,
            List<Map<String, Object>> rows,
            InvestigationPlan plan
    ) {
        String[] variables = resolveRelationshipVariables(plan);
        String sourceLabel = variables[0];
        String targetLabel = variables[1];

        var interpreted = CorrelationInterpretation.interpret(shape.coefficient());
        MaterializedCorrelation correlation = new MaterializedCorrelation(
                sourceLabel,
                targetLabel,
                shape.coefficient(),
                shape.sampleSize(),
                interpreted.strength(),
                interpreted.direction(),
                interpreted.summary());

        List<StructuralFinding> findings = List.of(
                new StructuralFinding(
                        String.format("Correlation coefficient between %s and %s: r=%.4f.",
                                sourceLabel, targetLabel, shape.coefficient()),
                        Math.abs(shape.coefficient()),
                        "warehouse CORR aggregate",
                        Math.abs(shape.coefficient()) >= 0.3),
                new StructuralFinding(
                        String.format("Sample size: %d observations used for the correlation.",
                                shape.sampleSize()),
                        shape.sampleSize(),
                        "correlation sample size",
                        shape.sampleSize() >= 30),
                new StructuralFinding(
                        String.format("Interpretation: %s", interpreted.summary()),
                        Math.abs(shape.coefficient()),
                        "correlation interpretation",
                        Math.abs(shape.coefficient()) >= 0.2)
        );

        return MaterializedQueryResult.correlation(
                correlation, findings, targetLabel, rows.size());
    }

    private MaterializedQueryResult materializeScalar(
            ScalarShape shape,
            List<Map<String, Object>> rows,
            InvestigationPlan plan
    ) {
        String metricLabel = labels.resolveMetric(shape.metricColumn());
        Long supportingCount = extractSupportingCount(rows.getFirst());

        MaterializedScalar scalar = new MaterializedScalar(
                shape.metricColumn(), metricLabel, shape.value(), supportingCount,
                shape.sharePct(), shape.labelValue());

        List<StructuralFinding> findings = new ArrayList<>();
        if (shape.sharePct() != null && !Double.isNaN(shape.sharePct())) {
            String segment = shape.labelValue() != null && !shape.labelValue().isBlank()
                    ? shape.labelValue() : "the filtered segment";
            findings.add(new StructuralFinding(
                    String.format("%s contributes %.1f%% of total %s (%.2f).",
                            segment, shape.sharePct(), metricLabel, shape.value()),
                    shape.sharePct(),
                    "filtered contribution aggregate",
                    true));
        } else {
            String countNote = supportingCount != null
                    ? String.format(" (based on %d rows)", supportingCount) : "";
            findings.add(new StructuralFinding(
                    String.format("%s: %.4f%s.", metricLabel, shape.value(), countNote),
                    Math.abs(shape.value()),
                    "scalar aggregate",
                    true));
        }

        return MaterializedQueryResult.scalar(scalar, findings, metricLabel, rows.size());
    }

    private String[] resolveRelationshipVariables(InvestigationPlan plan) {
        if (plan != null && plan.questionDrivenPlan() != null) {
            MetricResolution resolution = plan.questionDrivenPlan().resolution();
            if (resolution != null && resolution.isRelationshipAnalysis()) {
                String source = resolution.relationshipVariableLabel() != null
                        ? resolution.relationshipVariableLabel()
                        : labels.resolveMetric(resolution.relationshipVariable());
                String target = resolution.primaryMetricLabel() != null
                        ? resolution.primaryMetricLabel()
                        : labels.resolveMetric(resolution.primaryMetric());
                return new String[]{source, target};
            }
        }
        if (plan != null && plan.intentType() == AnalyticalIntentType.RELATIONSHIP) {
            return new String[]{"independent variable", "dependent metric"};
        }
        return new String[]{"source variable", "target metric"};
    }

    private Long extractSupportingCount(Map<String, Object> row) {
        if (row == null) return null;
        for (String key : row.keySet()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.equals("row_count") || lower.equals("count") || lower.equals("n")) {
                double v = RowAnalytics.toDouble(row.get(key));
                if (!Double.isNaN(v)) return (long) v;
            }
        }
        return null;
    }

    /**
     * Accepts warehouse rows that are already grouped (entity + metric columns).
     */
    private MaterializedQueryResult materializePreAggregated(
            List<Map<String, Object>> rows,
            GroupedShape shape,
            InvestigationPlan plan
    ) {
        MaterializationSpec spec = new MaterializationSpec(
                shape.dimensionColumn(),
                shape.dimensionColumn(),
                labels.resolveDimension(shape.dimensionColumn()),
                SpecType.SOURCE_DIMENSION,
                0);

        Map<String, Double> groupValues = new LinkedHashMap<>();
        Map<String, Double> warehouseShares = new LinkedHashMap<>();
        String shareCol = shape.shareColumn();
        for (Map<String, Object> row : rows) {
            Object rawKey = row.get(shape.dimensionColumn());
            if (rawKey == null) continue;
            String key = rawKey.toString().trim();
            if (key.isBlank()) continue;
            double value = RowAnalytics.toDouble(row.get(shape.metricColumn()));
            if (Double.isNaN(value)) continue;
            groupValues.merge(key, value, Double::sum);
            if (shareCol != null) {
                double share = RowAnalytics.toDouble(row.get(shareCol));
                if (!Double.isNaN(share)) {
                    warehouseShares.put(key, share);
                }
            }
        }

        if (groupValues.size() < GroupByExecutor.MIN_GROUPS_PUBLIC) {
            return MaterializedQueryResult.empty();
        }

        double grandTotal = groupValues.values().stream().mapToDouble(Double::doubleValue).sum();
        if (grandTotal <= 0) return MaterializedQueryResult.empty();

        List<Map.Entry<String, Double>> sorted = groupValues.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(50)
                .toList();

        List<Double> sortedValues = sorted.stream().map(Map.Entry::getValue).toList();
        double peerAvg = RowAnalytics.mean(sortedValues);

        List<MaterializedGroupEntry> entries = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Double> e = sorted.get(i);
            double val = e.getValue();
            double pct = RowAnalytics.percentileRank(sortedValues, val);
            Double warehouseShare = warehouseShares.get(e.getKey());
            double shr = warehouseShare != null && !Double.isNaN(warehouseShare)
                    ? warehouseShare
                    : 100.0 * val / grandTotal;
            double mult = peerAvg > 0 ? val / peerAvg : 1.0;
            entries.add(new MaterializedGroupEntry(
                    spec.displayLabel(), e.getKey(), val, 1.0, shr, val, i + 1, pct,
                    tier(pct), mult));
        }

        double gini = RowAnalytics.concentrationIndex(
                sortedValues.stream().sorted().collect(Collectors.toList()));
        MaterializedGrouping primary = new MaterializedGrouping(
                spec, entries, grandTotal, gini, groupValues.size());
        String valueLabel = labels.resolveMetric(shape.metricColumn());
        List<StructuralFinding> findings = generateFindings(List.of(primary), primary, valueLabel);
        return MaterializedQueryResult.grouped(List.of(primary), primary, findings, valueLabel, rows.size());
    }

    private String tier(double pct) {
        if (pct >= 90) return "TOP_DECILE";
        if (pct >= 75) return "TOP_QUARTILE";
        if (pct >= 55) return "ABOVE_AVERAGE";
        if (pct >= 35) return "AVERAGE";
        if (pct >= 15) return "BELOW_AVERAGE";
        return "BOTTOM_QUARTILE";
    }

    // ─── composite row builder ────────────────────────────────────────────

    /**
     * Creates a working row copy with a composite key column added.
     * e.g. rows with cols (zone_id, segment) → adds "_composite_zone_id__segment" = "Zone1 → SegA"
     */
    private List<Map<String, Object>> buildCompositeRows(
            List<Map<String, Object>> rows,
            String colA, String colB, String compositeKey
    ) {
        return rows.stream().map(row -> {
            Object vA = row.get(colA);
            Object vB = row.get(colB);
            if (vA == null || vB == null) return row;
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put(compositeKey, vA.toString().trim() + " → " + vB.toString().trim());
            return copy;
        }).collect(Collectors.toList());
    }

    // ─── primary selection ────────────────────────────────────────────────

    private MaterializedGrouping selectPrimary(
            List<MaterializedGrouping> groupings,
            InvestigationPlan          plan
    ) {
        // Score each grouping: temporal specs get a bonus for temporal intents
        boolean wantsTemporal = plan != null && plan.intentType() != null
                && (plan.intentType().name().contains("TREND")
                || plan.intentType().name().contains("FORECAST")
                || temporalFocusInPlan(plan));

        return groupings.stream()
                .max(Comparator.comparingDouble(g -> signal(g, wantsTemporal)))
                .orElse(groupings.get(0));
    }

    private double signal(MaterializedGrouping g, boolean wantsTemporal) {
        if (g.rankedEntries().isEmpty()) return 0;
        double topShare  = g.rankedEntries().get(0).sharePct();
        double gini      = g.giniConcentration();
        double temporal  = g.spec().isTemporal() && wantsTemporal ? 50.0 : 0;
        double groupBonus = Math.min(g.groupCount(), 24) * 0.5; // prefer richer groupings
        return topShare + gini * 10 + temporal + groupBonus;
    }

    private boolean temporalFocusInPlan(InvestigationPlan plan) {
        if (plan == null || plan.dimensionalFocus() == null) return false;
        return plan.dimensionalFocus().stream().anyMatch(f ->
                f != null && (f.toLowerCase().contains("time")
                        || f.toLowerCase().contains("hour")
                        || f.toLowerCase().contains("period")
                        || f.toLowerCase().contains("trend")));
    }

    // ─── findings ─────────────────────────────────────────────────────────

    private List<StructuralFinding> generateFindings(
            List<MaterializedGrouping> allGroupings,
            MaterializedGrouping       primary,
            String                     valueLabel
    ) {
        List<StructuralFinding> findings = new ArrayList<>();
        List<MaterializedGroupEntry> top = primary.rankedEntries();
        if (top.isEmpty()) return findings;

        // 1. Top contributor
        MaterializedGroupEntry no1 = top.get(0);
        findings.add(new StructuralFinding(
                String.format("[%s] (%s): %.2f total %s — %.1fx peer average, " +
                              "%.1f%% share of group total (rank #1 of %d groups).",
                        no1.entityKey(), primary.spec().displayLabel(),
                        no1.totalValue(), valueLabel,
                        no1.multiplierVsAvg(), no1.sharePct(), primary.groupCount()),
                no1.multiplierVsAvg(),
                "grouped contributor ranking – " + primary.spec().displayLabel(),
                no1.multiplierVsAvg() >= 1.8
        ));

        // 2. Pareto concentration
        int topN = Math.max(1, (int) Math.ceil(top.size() * 0.10));
        double topNShare = top.subList(0, topN).stream()
                .mapToDouble(MaterializedGroupEntry::sharePct).sum();
        if (topNShare >= 30) {
            findings.add(new StructuralFinding(
                    String.format("Top %.0f%% of %s groups account for %.1f%% of total %s — " +
                                  "Pareto concentration detected (Gini=%.2f).",
                            100.0 * topN / top.size(), primary.spec().displayLabel(),
                            topNShare, valueLabel, primary.giniConcentration()),
                    topNShare,
                    "pareto concentration – " + primary.spec().displayLabel(),
                    topNShare >= 50
            ));
        }

        // 3. Efficiency peak (best value-per-unit group, if different from #1 by value)
        top.stream()
                .filter(e -> e.efficiencyRatio() > 0)
                .max(Comparator.comparingDouble(MaterializedGroupEntry::efficiencyRatio))
                .filter(eff -> !eff.entityKey().equals(no1.entityKey()))
                .ifPresent(eff -> {
                    double peerEffAvg = top.stream()
                            .mapToDouble(MaterializedGroupEntry::efficiencyRatio)
                            .average().orElse(1);
                    if (peerEffAvg > 0 && eff.efficiencyRatio() / peerEffAvg >= 1.4) {
                        findings.add(new StructuralFinding(
                                String.format("Peak efficiency group [%s] (%s): %.3f value/unit — " +
                                              "%.1fx peer average efficiency (rank #%d by total).",
                                        eff.entityKey(), primary.spec().displayLabel(),
                                        eff.efficiencyRatio(), eff.efficiencyRatio() / peerEffAvg,
                                        eff.rank()),
                                eff.efficiencyRatio() / peerEffAvg,
                                "efficiency peak – " + primary.spec().displayLabel(),
                                eff.efficiencyRatio() / peerEffAvg >= 2.0
                        ));
                    }
                });

        // 4. Performance gap (top vs bottom)
        if (top.size() >= 5) {
            MaterializedGroupEntry bottom = top.get(top.size() - 1);
            if (bottom.totalValue() > 0) {
                double gap = no1.totalValue() / bottom.totalValue();
                if (gap >= 3.0) {
                    findings.add(new StructuralFinding(
                            String.format("Performance spread: top [%s] delivers %.1fx more %s than bottom [%s] " +
                                          "across %s groups — substantial intra-group polarisation.",
                                    no1.entityKey(), gap, valueLabel,
                                    bottom.entityKey(), primary.spec().displayLabel()),
                            gap, "top-to-bottom spread – " + primary.spec().displayLabel(), gap >= 5.0
                    ));
                }
            }
        }

        // 5. Secondary grouping finding (if a second grouping also has strong signal)
        allGroupings.stream()
                .filter(g -> g != primary && g.hasData() && !g.rankedEntries().isEmpty())
                .max(Comparator.comparingDouble(g -> g.rankedEntries().get(0).multiplierVsAvg()))
                .ifPresent(g2 -> {
                    MaterializedGroupEntry top2 = g2.rankedEntries().get(0);
                    if (top2.multiplierVsAvg() >= 1.5) {
                        findings.add(new StructuralFinding(
                                String.format("Secondary grouping [%s] shows [%s] as top contributor " +
                                              "with %.2f %s (%.1fx peer average, %.1f%% share).",
                                        g2.spec().displayLabel(), top2.entityKey(),
                                        top2.totalValue(), valueLabel,
                                        top2.multiplierVsAvg(), top2.sharePct()),
                                top2.multiplierVsAvg(),
                                "secondary grouping – " + g2.spec().displayLabel(),
                                top2.multiplierVsAvg() >= 2.0
                        ));
                    }
                });

        return findings.stream().limit(6).collect(Collectors.toList());
    }
}
