package com.example.BACKEND.catalogue.decision.candidate;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.IntentResolution;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.exploration.FallbackAnalyticalHeuristics;
import com.example.BACKEND.catalogue.decision.exploration.InterpretationCandidatePlan;
import com.example.BACKEND.catalogue.decision.exploration.MultiCandidateInterpretationEngine;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.semantic.QueryEntityResolver;
import com.example.BACKEND.catalogue.decision.semantic.ResolvedEntity;
import com.example.BACKEND.catalogue.decision.semantic.SemanticAnalyticalParser;
import com.example.BACKEND.catalogue.decision.semantic.SemanticAnalysisPlan;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates multiple executable analytical candidates before warehouse execution.
 * Never terminates on ambiguity — produces all viable hypotheses for parallel scoring.
 */
@Component
public class CandidateAnalysisGenerator {

    private final SemanticAnalyticalParser semanticParser;
    private final FallbackAnalyticalHeuristics heuristics;
    private final MultiCandidateInterpretationEngine interpretationEngine;
    private final QueryEntityResolver entityResolver;

    public CandidateAnalysisGenerator(
            SemanticAnalyticalParser semanticParser,
            FallbackAnalyticalHeuristics heuristics,
            MultiCandidateInterpretationEngine interpretationEngine,
            QueryEntityResolver entityResolver
    ) {
        this.semanticParser = semanticParser;
        this.heuristics = heuristics;
        this.interpretationEngine = interpretationEngine;
        this.entityResolver = entityResolver;
    }

    public List<AnalyticalCandidate> generate(IntentResolution intent, RegistryResolutionBundle bundle) {
        return generate(intent, bundle, null, null);
    }

    public List<AnalyticalCandidate> generate(
            IntentResolution intent,
            RegistryResolutionBundle bundle,
            QuestionSemantics semantics,
            MetricResolution resolution
    ) {
        String question = intent.question();
        String q = question != null ? question.toLowerCase(Locale.ROOT) : "";

        Map<String, AnalyticalCandidate> byId = new LinkedHashMap<>();

        if (resolution != null && resolution.isUsable()) {
            addCandidate(byId, plan(
                    resolution.primaryMetricLabel() + (resolution.dimensionLabel() != null
                            ? " by " + resolution.dimensionLabel() : ""),
                    "Semantics-driven plan",
                    resolution.primaryMetric(), resolution.primaryMetricLabel(),
                    resolution.dimension(), resolution.grouping(),
                    AggregationType.SUM,
                    semantics != null ? semantics.intent() : AnalyticalIntentType.GENERAL_ANALYSIS,
                    resolution.confidence(), "semantic_extractor"),
                    resolution.dimension(), resolution.grouping());
        }

        MultiCandidateInterpretationEngine.CandidateSet base =
                interpretationEngine.generate(intent, bundle);
        for (InterpretationCandidatePlan p : base.candidates()) {
            addCandidate(byId, p, p.source());
        }

        if (hasImpactLanguage(q)) {
            expandImpactCandidates(byId, question, q);
        }

        SemanticAnalysisPlan semantic = semanticParser.parseExploratory(question, bundle);
        if (semantic.parsed() && semantic.dimensionImpactPlan() != null) {
            var dip = semantic.dimensionImpactPlan();
            addCandidate(byId, plan(
                    "Semantic: " + dip.metricLabel() + " by " + dip.dimensionLabel(),
                    semantic.planSummary(),
                    dip.metricColumn(), dip.metricLabel(), dip.dimensionColumn(),
                    dip.bucketStrategy(), AggregationType.SUM, AnalyticalIntentType.CONTRIBUTION,
                    semantic.confidence(), "semantic_parser"),
                    dip.dimensionColumn(), dip.bucketStrategy());
        }

        return new ArrayList<>(byId.values());
    }

    private void expandImpactCandidates(Map<String, AnalyticalCandidate> byId, String question, String q) {
        ResolvedEntity dim = entityResolver.firstDimension(question);
        String dimCol = dim != null ? dim.columnKey() : inferDimension(q);
        if (dimCol == null) return;
        String bucket = bucketColumn(dimCol);
        String dimLabel = dim != null ? dim.label() : dimCol.replace('_', ' ');

        if (q.contains("revenue") || q.contains("fare") || q.contains("amount")) {
            addCandidate(byId, plan(
                    "Revenue grouped by " + dimLabel,
                    "SUM(total_amount) grouped by " + bucket,
                    "total_amount", "Total Revenue", dimCol, bucket,
                    AggregationType.SUM, AnalyticalIntentType.CONTRIBUTION, 0.82, "impact_template"),
                    dimCol, bucket);

            addCandidate(byId, plan(
                    "Revenue per mile by " + dimLabel,
                    "Efficiency: revenue per mile across " + bucket,
                    "revenue_per_mile", "Revenue per Mile", dimCol, bucket,
                    AggregationType.AVG, AnalyticalIntentType.EFFICIENCY, 0.76, "impact_template"),
                    dimCol, bucket);

            addCandidate(byId, plan(
                    "Average fare by " + dimLabel,
                    "AVG(fare_amount) by " + bucket,
                    "fare_amount", "Base Fare", dimCol, bucket,
                    AggregationType.AVG, AnalyticalIntentType.DISTRIBUTION, 0.74, "impact_template"),
                    dimCol, bucket);

            addCandidate(byId, plan(
                    "Contribution share by " + dimLabel,
                    "Share of revenue across " + bucket + " bands",
                    "total_amount", "Total Revenue", dimCol, bucket,
                    AggregationType.SUM, AnalyticalIntentType.CONTRIBUTION, 0.8, "impact_template"),
                    dimCol, bucket);
        }

        for (InterpretationCandidatePlan h : heuristics.generate(question)) {
            addCandidate(byId, h, h.source());
        }
    }

    private void addCandidate(
            Map<String, AnalyticalCandidate> byId,
            InterpretationCandidatePlan p,
            String source
    ) {
        String dim = p.secondaryMetric() != null && !p.secondaryMetric().isBlank()
                ? p.secondaryMetric() : inferDimensionFromGrouping(p.grouping());
        String bucket = bucketColumn(p.grouping() != null && !p.grouping().isBlank()
                ? p.grouping() : dim);
        String id = candidateId(p.primaryMetric(), bucket, p.aggregation().name(), source);
        byId.putIfAbsent(id, new AnalyticalCandidate(id, p, dim, bucket));
    }

    private void addCandidate(
            Map<String, AnalyticalCandidate> byId,
            InterpretationCandidatePlan p,
            String dimCol,
            String bucketCol
    ) {
        String id = candidateId(p.primaryMetric(), bucketCol, p.aggregation().name(), p.source());
        byId.putIfAbsent(id, new AnalyticalCandidate(id, p, dimCol, bucketCol));
    }

    private String candidateId(String metric, String grouping, String agg, String source) {
        return (metric + "__" + grouping + "__" + agg + "__" + source)
                .toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private String inferDimension(String q) {
        if (q.contains("distance") || q.contains("mile")) return "trip_distance";
        if (q.contains("zone") || q.contains("pickup")) return "pickup_zone";
        if (q.contains("vendor")) return "vendor_id";
        if (q.contains("weekend")) return "weekend_flag";
        if (q.contains("hour")) return "pickup_hour";
        if (q.contains("tip")) return "tip_amount";
        return null;
    }

    private String inferDimensionFromGrouping(String grouping) {
        if (grouping == null || grouping.isBlank()) return null;
        if (grouping.endsWith("_bucket")) {
            return grouping.substring(0, grouping.length() - "_bucket".length());
        }
        return grouping;
    }

    private String bucketColumn(String dimOrBucket) {
        if (dimOrBucket == null) return "";
        if (dimOrBucket.endsWith("_bucket") || dimOrBucket.endsWith("_flag")) return dimOrBucket;
        if ("trip_distance".equals(dimOrBucket)) return "trip_distance_bucket";
        if ("pickup_zone".equals(dimOrBucket)) return "pickup_zone_bucket";
        if ("fare_amount".equals(dimOrBucket) || "tip_amount".equals(dimOrBucket)) {
            return dimOrBucket + "_bucket";
        }
        return dimOrBucket + "_bucket";
    }

    private boolean hasImpactLanguage(String q) {
        return q.contains("contribute") || q.contains("affect") || q.contains("impact")
                || q.contains("drive") || q.contains("influence") || q.contains("relate");
    }

    private InterpretationCandidatePlan plan(
            String label, String desc, String metric, String metricLabel,
            String dimCol, String grouping, AggregationType agg,
            AnalyticalIntentType intent, double conf, String source
    ) {
        return new InterpretationCandidatePlan(
                label, desc, metric, metricLabel, dimCol,
                grouping != null ? grouping : "", agg, intent, conf, source);
    }
}
