package com.example.BACKEND.catalogue.decision.execution.framework;

import com.example.BACKEND.catalogue.decision.analytics.aggregation.AggregationIntelligenceEngine;
import com.example.BACKEND.catalogue.decision.analytics.aggregation.AggregationIntelligenceResult;
import com.example.BACKEND.catalogue.decision.execution.materialization.AnalyticalQueryMaterializer;
import com.example.BACKEND.catalogue.decision.execution.materialization.AnalyticalWarehouseResultDetector;
import com.example.BACKEND.catalogue.decision.execution.materialization.GroupedWarehouseResultDetector;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.*;
import com.example.BACKEND.catalogue.decision.execution.StatisticalSignificanceGuard;
import com.example.BACKEND.catalogue.decision.execution.StatisticalSignificanceGuard.FilteredEntities;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Intent-Driven Computation Framework.
 *
 * The generalized, dataset-agnostic analytical computation engine.
 *
 * This service replaces the domain-specific {@link
 * com.example.BACKEND.catalogue.decision.execution.DynamicAnalyticalExecutionEngine}.
 *
 * Pipeline:
 *   1. Profile schema  — discover column roles (DIMENSION/VALUE/VOLUME/TIME/RATE)
 *   2. Build blueprint — select computation strategy for this intent type
 *   3. Expand entities — construct analytical entities from dimension columns
 *   4. Derive metrics  — compute efficiency ratios, shares, z-scores, deltas
 *   5. Filter noise    — remove low-sample and outlier entities
 *   6. Rank            — produce primary and efficiency rankings with percentiles
 *   7. Generate findings — produce specific, quantified, non-obvious statements
 *
 * All steps are driven by schema discovery and intent type — no column names,
 * business concepts, or domain assumptions are hardcoded anywhere.
 *
 * Works across any enterprise dataset: finance, SaaS, logistics, healthcare,
 * manufacturing, retail, energy, supply chain, or arbitrary warehouse schemas.
 */
@Service
public class IntentDrivenComputationFramework {

    private static final Logger log = LoggerFactory.getLogger(IntentDrivenComputationFramework.class);

    private static final int MAX_RANKED = 20;

    private final SchemaProfiler             schemaProfiler;
    private final IntentComputationMap        intentMap;
    private final EntityExpansionEngine       entityExpander;
    private final DerivedMetricPlanner        metricPlanner;
    private final StatisticalSignificanceGuard sigGuard;
    private final AggregationIntelligenceEngine aggregationEngine;
    private final AnalyticalQueryMaterializer   materializer;

    public IntentDrivenComputationFramework(
            SchemaProfiler              schemaProfiler,
            IntentComputationMap        intentMap,
            EntityExpansionEngine       entityExpander,
            DerivedMetricPlanner        metricPlanner,
            StatisticalSignificanceGuard sigGuard,
            AggregationIntelligenceEngine aggregationEngine,
            AnalyticalQueryMaterializer   materializer
    ) {
        this.schemaProfiler = schemaProfiler;
        this.intentMap      = intentMap;
        this.entityExpander = entityExpander;
        this.metricPlanner  = metricPlanner;
        this.sigGuard       = sigGuard;
        this.aggregationEngine = aggregationEngine;
        this.materializer   = materializer;
    }

    public ExecutionFindings execute(ComputationResultSet resultSet, InvestigationPlan plan) {
        if (resultSet == null || resultSet.results().isEmpty())
            return ExecutionFindings.empty();

        AnalyticalIntentType intentType = plan != null
                ? plan.intentType()
                : AnalyticalIntentType.GENERAL_ANALYSIS;

        List<Map<String, Object>> templateRows = mergeTemplateRows(resultSet);
        List<Map<String, Object>> allRows = mergeRows(resultSet);
        List<Map<String, Object>> materializationRows =
                templateRows.isEmpty() ? allRows : templateRows;

        if (materializationRows.isEmpty()) return ExecutionFindings.empty();

        // ── Step 1: Schema profiling ─────────────────────────────────────
        SchemaProfile profile = schemaProfiler.profile(materializationRows);

        boolean warehouseShaped = AnalyticalWarehouseResultDetector.detectCorrelation(materializationRows).isPresent()
                || AnalyticalWarehouseResultDetector.detectScalar(materializationRows).isPresent()
                || GroupedWarehouseResultDetector.detect(materializationRows) != null;

        // ── Step 1b: Materialize warehouse-shaped results (scalar, correlation, grouped aggregate)
        MaterializedQueryResult materializedResult =
                materializer.materialize(materializationRows, profile, plan);
        if (materializedResult.hasContent() && warehouseShaped) {
            log.info("[framework] warehouse-shaped {} materialized from {} row(s) — skipping entity expansion",
                    materializedResult.resultType(), materializationRows.size());
            return new ExecutionFindings(
                    List.of(), List.of(), List.of(),
                    new StatisticalContext(
                            materializationRows.size(), materializationRows.size(), 0, 0, 0, 0,
                            "Warehouse aggregate materialized from " + materializationRows.size() + " row(s)."),
                    materializedResult.findings(),
                    materializedResult);
        }

        if (allRows.size() < 3) return ExecutionFindings.empty();

        if (!profile.hasDimensions() && !profile.hasValues()) {
            log.info("[framework] schema has no usable dimensions or values — returning empty");
            return ExecutionFindings.empty();
        }

        // ── Step 2: Blueprint selection ──────────────────────────────────
        ComputationBlueprint blueprint = intentMap.blueprintFor(intentType, profile);

        log.info("[framework] intent={} strategy={} depth={} efficiency={} concentration={}",
                intentType, blueprint.computationStrategy(),
                blueprint.entityGroupingDepth(),
                blueprint.requiresEfficiency(), blueprint.requiresConcentration());

        // ── Step 3: Entity expansion ─────────────────────────────────────
        List<ConstructedEntity> rawEntities = entityExpander.expand(allRows, profile, blueprint);

        if (rawEntities.isEmpty()) {
            log.info("[framework] no entities constructed — dimensions: {}", profile.dimensions().size());
            return ExecutionFindings.empty();
        }

        // ── Step 4: Derived metric enrichment ────────────────────────────
        List<ConstructedEntity> enriched = metricPlanner.enrich(rawEntities, profile, blueprint);

        // ── Step 5: Statistical significance filtering ────────────────────
        FilteredEntities filtered = sigGuard.filter(enriched);

        // ── Step 5.5a: Analytical query materialization (primary evidence layer) ─
        if (!materializedResult.hasContent()) {
            materializedResult = materializer.materialize(materializationRows, profile, plan);
        }

        if (!materializedResult.hasContent() && allRows.size() >= 2) {
            log.warn("[framework] materialization empty despite {} warehouse rows; profile dims={} values={} columns={}",
                    allRows.size(), profile.dimensions().size(), profile.valueColumns().size(),
                    profile.columns().stream()
                            .map(c -> c.columnName() + ":" + c.role().name())
                            .collect(Collectors.joining(", ")));
        }

        // ── Step 5.5b: Aggregation intelligence (supplementary layer) ─────
        AggregationIntelligenceResult aggregationResult =
                aggregationEngine.analyze(allRows, profile, blueprint);

        // ── Step 6: Rankings ─────────────────────────────────────────────
        ColumnProfile primaryValCol  = profile.primaryValue();
        String primaryKey = primaryValCol != null ? primaryValCol.columnName() : null;
        String effKey     = resolveEfficiencyKey(filtered.retained());

        List<RankedEntity> primaryRanking    = blueprint.requiresRanking() && primaryKey != null
                ? rank(filtered.retained(), primaryKey)
                : List.of();

        if (primaryRanking.isEmpty() && !aggregationResult.contributorRanking().isEmpty()) {
            primaryRanking = aggregationResult.contributorRanking();
        }

        List<RankedEntity> efficiencyRanking = blueprint.requiresEfficiency() && effKey != null
                ? rank(filtered.retained(), effKey)
                : List.of();

        if (efficiencyRanking.isEmpty() && !aggregationResult.efficiencyRanking().isEmpty()) {
            efficiencyRanking = aggregationResult.efficiencyRanking();
        }

        // ── Step 7: Statistical context ──────────────────────────────────
        double peerAvg = primaryRanking.isEmpty() ? 0 : primaryRanking.get(0).peerAverage();
        double topDecile = primaryRanking.stream()
                .filter(r -> "TOP_DECILE".equals(r.tier()))
                .mapToDouble(RankedEntity::value).min().orElse(0);

        StatisticalContext stats = new StatisticalContext(
                rawEntities.size(),
                filtered.retained().size(),
                filtered.outliers().size(),
                filtered.report().minimumSampleUsed(),
                peerAvg, topDecile,
                filtered.report().note()
        );

        // ── Step 8: Entity-level structural findings ──────────────────────
        List<StructuralFinding> findings = generateFindings(
                filtered.retained(), primaryRanking, efficiencyRanking,
                stats, profile, blueprint, intentType
        );
        // Also merge aggregation engine findings
        findings = mergeFindings(findings, aggregationResult.findings(), 10);

        // Include aggregation-engine entities as extra evidence objects
        List<ConstructedEntity> finalEntities = mergeEntities(filtered.retained(), aggregationResult.groupedEntities(), 50);

        // Merge materialized findings (highest priority — pre-computed grouped evidence)
        List<StructuralFinding> allFindings = mergeFindings(
                materializedResult.findings(), findings, 10);

        log.info("[framework] entities={} retained={} primaryRanked={} effRanked={} findings={} materialized={}",
                rawEntities.size(), filtered.retained().size(),
                primaryRanking.size(), efficiencyRanking.size(), allFindings.size(),
                materializedResult.hasContent());

        return new ExecutionFindings(
                finalEntities, primaryRanking, efficiencyRanking, stats, allFindings,
                materializedResult);
    }

    // ─── ranking ─────────────────────────────────────────────────────────

    private List<RankedEntity> rank(List<ConstructedEntity> entities, String metricKey) {
        List<ConstructedEntity> valid = entities.stream()
                .filter(e -> {
                    Double v = e.metrics().get(metricKey);
                    return v != null && !Double.isNaN(v) && v >= 0;
                })
                .sorted(Comparator.comparingDouble(e -> -e.metrics().get(metricKey)))
                .limit(MAX_RANKED)
                .collect(Collectors.toList());

        if (valid.isEmpty()) return List.of();

        List<Double> allVals = valid.stream()
                .map(e -> e.metrics().get(metricKey)).collect(Collectors.toList());
        double avg = RowAnalytics.mean(allVals);

        List<RankedEntity> ranked = new ArrayList<>();
        for (int i = 0; i < valid.size(); i++) {
            ConstructedEntity e = valid.get(i);
            double val       = e.metrics().get(metricKey);
            double pctRank   = RowAnalytics.percentileRank(allVals, val);
            double multiplier = avg > 0 ? val / avg : 1.0;
            ranked.add(new RankedEntity(i + 1, e.entityKey(), metricKey, val, avg, multiplier, pctRank, tier(pctRank)));
        }
        return ranked;
    }

    private String tier(double pct) {
        if (pct >= 90) return "TOP_DECILE";
        if (pct >= 75) return "TOP_QUARTILE";
        if (pct >= 55) return "ABOVE_AVERAGE";
        if (pct >= 35) return "AVERAGE";
        if (pct >= 15) return "BELOW_AVERAGE";
        return "BOTTOM_QUARTILE";
    }

    private String resolveEfficiencyKey(List<ConstructedEntity> entities) {
        if (entities.isEmpty()) return null;
        // Prefer generic keys the DerivedMetricPlanner produces
        for (String candidate : List.of("efficiency_ratio", "throughput")) {
            if (entities.get(0).metrics().containsKey(candidate)) return candidate;
        }
        // Fallback: find first ratio_ key
        return entities.get(0).metrics().keySet().stream()
                .filter(k -> k.startsWith("ratio_")).findFirst().orElse(null);
    }

    // ─── findings ────────────────────────────────────────────────────────

    private List<StructuralFinding> generateFindings(
            List<ConstructedEntity>  entities,
            List<RankedEntity>       primary,
            List<RankedEntity>       efficiency,
            StatisticalContext       stats,
            SchemaProfile            profile,
            ComputationBlueprint     blueprint,
            AnalyticalIntentType     intentType
    ) {
        List<StructuralFinding> findings = new ArrayList<>();
        String metricLabel = profile.primaryValue() != null
                ? profile.primaryValue().columnName().replace("_", " ") : "primary metric";

        // 1. Top performer finding
        if (!primary.isEmpty()) {
            RankedEntity top = primary.get(0);
            if (top.multiplierVsAverage() >= 1.3) {
                findings.add(new StructuralFinding(
                        String.format("#1 [%s]: %.2f %s — %.1fx the entity average (%.2f). " +
                                      "Positioned at %.0fth percentile of %d entities.",
                                top.entityKey(), top.value(), metricLabel,
                                top.multiplierVsAverage(), top.peerAverage(),
                                top.percentileRank(), stats.entitiesAfterSignificanceFilter()),
                        top.multiplierVsAverage(),
                        "primary metric ranking",
                        top.multiplierVsAverage() >= 2.0
                ));
            }
        }

        // 2. Efficiency leader (if different from primary)
        if (!efficiency.isEmpty()) {
            RankedEntity effTop = efficiency.get(0);
            String effLabel = effTop.rankingDimension().replace("_", " ");
            if (effTop.multiplierVsAverage() >= 1.4) {
                findings.add(new StructuralFinding(
                        String.format("Efficiency leader [%s]: %s = %.3f (%.1fx peer average of %.3f).",
                                effTop.entityKey(), effLabel,
                                effTop.value(), effTop.multiplierVsAverage(), effTop.peerAverage()),
                        effTop.multiplierVsAverage(),
                        "efficiency ranking (" + effTop.rankingDimension() + ")",
                        effTop.multiplierVsAverage() >= 1.8
                ));
            }
        }

        // 3. Scale vs efficiency divergence
        if (!primary.isEmpty() && !efficiency.isEmpty() && primary.size() >= 4) {
            Set<String> top3Prim = primary.subList(0, Math.min(3, primary.size())).stream()
                    .map(RankedEntity::entityKey).collect(Collectors.toSet());
            efficiency.subList(0, Math.min(3, efficiency.size())).stream()
                    .filter(e -> !top3Prim.contains(e.entityKey()))
                    .findFirst()
                    .ifPresent(gem -> findings.add(new StructuralFinding(
                            String.format("[%s] ranks top-3 in efficiency (%s=%.3f) but outside top-3 by " +
                                          "volume — high-efficiency entity underrepresented in scale ranking.",
                                    gem.entityKey(), gem.rankingDimension(), gem.value()),
                            gem.multiplierVsAverage(), "efficiency vs scale divergence", true
                    )));
        }

        // 4. Concentration finding
        if (!primary.isEmpty() && blueprint.requiresConcentration()) {
            int    top3 = Math.min(3, primary.size());
            double top3Sum   = primary.subList(0, top3).stream().mapToDouble(RankedEntity::value).sum();
            double totalSum  = primary.stream().mapToDouble(RankedEntity::value).sum();
            if (totalSum > 0) {
                double share  = 100.0 * top3Sum / totalSum;
                double volPct = 100.0 * top3 / primary.size();
                if (share >= 35) {
                    findings.add(new StructuralFinding(
                            String.format("Top %d entities account for %.1f%% of total %s despite " +
                                          "representing only %.0f%% of entity count — concentration detected.",
                                    top3, share, metricLabel, volPct),
                            share, "top-3 concentration analysis", share >= 50
                    ));
                }
            }
        }

        // 5. Performance gap (top vs bottom)
        if (primary.size() >= 5) {
            RankedEntity bottom = primary.get(primary.size() - 1);
            RankedEntity top    = primary.get(0);
            if (bottom.value() > 0) {
                double gap = top.value() / bottom.value();
                if (gap >= 3.0) {
                    findings.add(new StructuralFinding(
                            String.format("Performance gap: top entity [%s] outperforms bottom [%s] " +
                                          "by %.1fx on %s — substantial within-group polarisation.",
                                    top.entityKey(), bottom.entityKey(), gap, metricLabel),
                            gap, "top-to-bottom gap", gap >= 5.0
                    ));
                }
            }
        }

        // 6. Anomaly finding (z-score detection)
        if (blueprint.requiresOutlierDetection()) {
            entities.stream()
                    .filter(e -> {
                        String zKey = e.metrics().keySet().stream()
                                .filter(k -> k.startsWith("z_score_")).findFirst().orElse(null);
                        return zKey != null && Math.abs(e.metrics().get(zKey)) >= 2.0;
                    })
                    .limit(2)
                    .forEach(e -> {
                        String zKey = e.metrics().keySet().stream()
                                .filter(k -> k.startsWith("z_score_")).findFirst().orElse("");
                        double z = e.metrics().get(zKey);
                        findings.add(new StructuralFinding(
                                String.format("[%s] deviates %.1f standard deviations from peer mean " +
                                              "on %s — statistical anomaly (z=%.2f).",
                                        e.entityKey(), Math.abs(z), metricLabel, z),
                                Math.abs(z), "z-score anomaly detection", Math.abs(z) >= 3.0
                        ));
                    });
        }

        return findings.stream().limit(7).collect(Collectors.toList());
    }

    // ─── row utilities ────────────────────────────────────────────────────

    private List<Map<String, Object>> mergeTemplateRows(ComputationResultSet rs) {
        return rs.results().stream()
                .filter(r -> r.key() != null && r.key().startsWith("tpl__"))
                .filter(r -> r.rows() != null)
                .flatMap(r -> r.rows().stream())
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> mergeRows(ComputationResultSet rs) {
        return rs.results().stream()
                .filter(r -> r.rows() != null)
                .flatMap(r -> r.rows().stream())
                .collect(Collectors.toList());
    }

    private List<StructuralFinding> mergeFindings(
            List<StructuralFinding> base,
            List<StructuralFinding> extra,
            int limit
    ) {
        LinkedHashMap<String, StructuralFinding> byText = new LinkedHashMap<>();
        base.forEach(f -> byText.put(f.findingText(), f));
        extra.forEach(f -> byText.putIfAbsent(f.findingText(), f));
        return byText.values().stream().limit(limit).collect(Collectors.toList());
    }

    private List<ConstructedEntity> mergeEntities(
            List<ConstructedEntity> base,
            List<ConstructedEntity> extra,
            int limit
    ) {
        LinkedHashMap<String, ConstructedEntity> byKey = new LinkedHashMap<>();
        base.forEach(e -> byKey.put(e.entityType() + "::" + e.entityKey(), e));
        extra.forEach(e -> byKey.putIfAbsent(e.entityType() + "::" + e.entityKey(), e));
        return byKey.values().stream().limit(limit).collect(Collectors.toList());
    }
}
