package com.example.BACKEND.catalogue.decision.analytics;

import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult.*;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Analytical Depth Engine.
 *
 * Operates AFTER warehouse compute and BEFORE evidence assembly.
 * Applies six structural analysis engines to raw rows to compute non-obvious
 * findings that flat metric summaries would miss.
 *
 * The engine is intent-aware: it activates different sub-engines based on
 * the investigation plan's intent type to avoid pointless computation.
 *
 * Intent → engine activation:
 *
 *   CONTRIBUTION     → Distribution + Efficiency
 *   RANKING          → Distribution + Composite + Efficiency
 *   SEGMENTATION     → Segmentation + Distribution + Efficiency
 *   COMPARISON       → Relationship + Segmentation
 *   ANOMALY          → Distribution + Inflection + Relationship
 *   TREND            → Inflection + Relationship
 *   ROOT_CAUSE       → Segmentation + Inflection + Relationship
 *   STRATEGIC_PRIO   → All six engines
 *   GENERAL/default  → Efficiency + Distribution
 *
 * Results are passed to the synthesis prompt as pre-computed structural findings.
 * The LLM is instructed to reason from them rather than computing them itself.
 */
@Service
public class AnalyticalDepthEngine {

    private static final Logger log = LoggerFactory.getLogger(AnalyticalDepthEngine.class);

    private final SegmentationAnalysisEngine     segmentation;
    private final RelationshipAnalysisEngine     relationship;
    private final EfficiencyAnalysisEngine       efficiency;
    private final ComparativeDistributionEngine  distribution;
    private final InflectionPointDetector        inflection;
    private final MultiMetricCompositeAnalyzer   composite;

    public AnalyticalDepthEngine(
            SegmentationAnalysisEngine    segmentation,
            RelationshipAnalysisEngine    relationship,
            EfficiencyAnalysisEngine      efficiency,
            ComparativeDistributionEngine distribution,
            InflectionPointDetector       inflection,
            MultiMetricCompositeAnalyzer  composite
    ) {
        this.segmentation = segmentation;
        this.relationship = relationship;
        this.efficiency   = efficiency;
        this.distribution = distribution;
        this.inflection   = inflection;
        this.composite    = composite;
    }

    public AnalyticalDepthResult analyse(
            ComputationResultSet resultSet,
            InvestigationPlan    plan
    ) {
        if (resultSet == null || resultSet.results().isEmpty()) {
            return AnalyticalDepthResult.empty();
        }

        AnalyticalIntentType intentType = plan != null
                ? plan.intentType()
                : AnalyticalIntentType.GENERAL_ANALYSIS;

        // Use all rows from all query results merged
        List<Map<String, Object>> allRows = mergeRows(resultSet);
        if (allRows.isEmpty()) return AnalyticalDepthResult.empty();

        Set<String> active = activeEngines(intentType);

        List<SegmentBucket>      buckets       = active.contains("SEG") ? segmentation.analyse(allRows) : List.of();
        List<RelationshipSignal> relationships = active.contains("REL") ? relationship.analyse(allRows) : List.of();
        List<EfficiencyMetric>   effMetrics    = active.contains("EFF") ? efficiency.analyse(allRows) : List.of();
        DistributionProfile      distProfile   = active.contains("DIST") ? distribution.analyse(allRows) : null;
        List<InflectionPoint>    inflections   = active.contains("INF") ? inflection.detect(allRows) : List.of();
        List<CompositeScore>     composites    = active.contains("COMP") ? composite.analyse(allRows) : List.of();

        List<String> insights = deriveNonObviousInsights(
                buckets, relationships, effMetrics, distProfile, inflections, composites);

        log.info("[depth] intentType={} buckets={} relationships={} efficiency={} " +
                        "inflections={} composites={} nonObvious={}",
                intentType, buckets.size(), relationships.size(), effMetrics.size(),
                inflections.size(), composites.size(), insights.size());

        return new AnalyticalDepthResult(
                buckets, relationships, effMetrics,
                distProfile, inflections, composites, insights
        );
    }

    // ─── engine activation map ───────────────────────────────────────────

    private Set<String> activeEngines(AnalyticalIntentType intentType) {
        return switch (intentType.canonical()) {
            case CONTRIBUTION, COMPOSITION -> Set.of("DIST", "EFF");
            case RANKING                   -> Set.of("DIST", "COMP", "EFF");
            case DISTRIBUTION, SEGMENTATION -> Set.of("SEG", "DIST", "EFF");
            case EFFICIENCY                -> Set.of("EFF", "DIST", "COMP");
            case CORRELATION               -> Set.of("REL", "DIST");
            case COMPARISON                -> Set.of("REL", "SEG");
            case ANOMALY_DETECTION         -> Set.of("DIST", "INF", "REL");
            case TREND_ANALYSIS            -> Set.of("INF", "REL");
            case ROOT_CAUSE_INVESTIGATION  -> Set.of("SEG", "INF", "REL");
            case STRATEGIC_PRIORITIZATION  -> Set.of("SEG", "REL", "EFF", "DIST", "INF", "COMP");
            case RETENTION                 -> Set.of("REL", "INF");
            default                        -> Set.of("EFF", "DIST");
        };
    }

    // ─── non-obvious insight derivation ─────────────────────────────────

    private List<String> deriveNonObviousInsights(
            List<SegmentBucket>      buckets,
            List<RelationshipSignal> relationships,
            List<EfficiencyMetric>   effMetrics,
            DistributionProfile      dist,
            List<InflectionPoint>    inflections,
            List<CompositeScore>     composites
    ) {
        List<String> insights = new ArrayList<>();

        // Distribution insight
        if (dist != null && dist.top10SharePercent() >= 40) {
            insights.add(String.format(
                    "Top 10%% of entities account for %.1f%% of %s — " +
                    "performance is highly concentrated, not broadly distributed.",
                    dist.top10SharePercent(), dist.metricKey().replace("_", " ")));
        }

        // Efficiency tier gap
        effMetrics.stream()
                .filter(e -> "TOP".equals(e.tier()) || "BELOW_AVERAGE".equals(e.tier()))
                .findFirst()
                .ifPresent(e -> insights.add(String.format(
                        "Average %s = %.2f — this efficiency metric separates high-quality from high-volume performers.",
                        e.ratioLabel(), e.value())));

        // Inflection insights
        for (InflectionPoint ip : inflections) {
            insights.add(ip.implication());
        }

        // Relationship insights
        for (RelationshipSignal r : relationships) {
            if (Math.abs(r.correlationCoefficient()) >= 0.5) {
                insights.add(r.characterization());
            }
        }

        // Segmentation extreme bucket gap
        if (buckets.size() >= 2) {
            SegmentBucket best  = buckets.stream()
                    .max(Comparator.comparingDouble(SegmentBucket::avgValue)).orElse(null);
            SegmentBucket worst = buckets.stream()
                    .min(Comparator.comparingDouble(SegmentBucket::avgValue)).orElse(null);
            if (best != null && worst != null && worst.avgValue() > 0) {
                double ratio = best.avgValue() / worst.avgValue();
                if (ratio >= 1.5) {
                    insights.add(String.format(
                            "%s generates %.1fx more value per unit than %s — " +
                            "substantial efficiency gap across %s segments.",
                            best.bucketLabel(), ratio, worst.bucketLabel(),
                            best.dimensionKey().replace("_", " ")));
                }
            }
        }

        // Composite tier distribution
        if (!composites.isEmpty()) {
            long leaders = composites.stream().filter(c -> "LEADER".equals(c.tier())).count();
            long atRisk  = composites.stream().filter(c -> "AT_RISK".equals(c.tier())).count();
            if (leaders > 0 && atRisk > 0) {
                insights.add(String.format(
                        "%d entities classified as LEADER vs %d AT_RISK — " +
                        "substantial performance polarisation across the portfolio.",
                        leaders, atRisk));
            }
        }

        return insights;
    }

    // ─── row merging ─────────────────────────────────────────────────────

    private List<Map<String, Object>> mergeRows(ComputationResultSet resultSet) {
        List<Map<String, Object>> merged = new ArrayList<>();
        for (QueryResult qr : resultSet.results()) {
            if (qr.rows() != null) merged.addAll(qr.rows());
        }
        return merged;
    }
}
