package com.example.BACKEND.catalogue.decision.presentation.executive;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult;
import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult.SegmentBucket;
import com.example.BACKEND.catalogue.decision.clarification.ResolvedAnalyticalQuestion;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.execution.materialization.AnalyticalResultType;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedGroupEntry;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedScalar;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds executive cards from grouped aggregates and depth analysis when formal
 * findings were filtered out by governance.
 */
@Component
public class ExploratoryExecutivePresenter {

    private final BusinessSemanticAliases aliases;
    private final AnswerCompressionPolicy compression;
    private final HumanNarrativeFormatter human;
    private final SemanticMetricFormatter metrics;

    public ExploratoryExecutivePresenter(
            BusinessSemanticAliases aliases,
            AnswerCompressionPolicy compression,
            HumanNarrativeFormatter human,
            SemanticMetricFormatter metrics
    ) {
        this.aliases = aliases;
        this.compression = compression;
        this.human = human;
        this.metrics = metrics;
    }

    public ExecutiveInsightCard present(
            InvestigationPlan plan,
            ChartSpec chartSpec,
            AnalyticalDepthResult depth,
            ExecutionFindings executionFindings,
            ResolvedAnalyticalQuestion resolved,
            double confidence,
            ExecutiveConfidenceLabel confidenceLabel
    ) {
        if (executionFindings != null && executionFindings.materializedResult() != null) {
            MaterializedQueryResult mat = executionFindings.materializedResult();
            if (mat.resultType() == AnalyticalResultType.SCALAR_RESULT
                    && mat.scalar() != null && mat.scalar().isValid()) {
                return presentScalar(mat.scalar(), mat.findings(), confidenceLabel);
            }
        }

        MaterializedGrouping grouping = executionFindings != null
                && executionFindings.materializedResult() != null
                ? executionFindings.materializedResult().primaryGrouping() : null;

        String metric = resolveMetric(plan, executionFindings);
        String dimension = resolveDimension(plan, resolved, grouping);

        ChartSpec chart = chartSpec != null ? chartSpec : chartFromGrouping(grouping, metric, dimension);
        if (chart == null && depth != null) chart = chartFromDepth(depth);

        String takeaway = buildTakeaway(grouping, depth, metric, dimension);
        if (takeaway.isBlank()) {
            if (chart != null && chart.getData() != null && !chart.getData().isEmpty()) {
                takeaway = "Review the grouped breakdown in the chart below.";
            } else if (grouping != null && grouping.rankedEntries() != null
                    && !grouping.rankedEntries().isEmpty()) {
                takeaway = "Grouped warehouse results are available for " + dimension + ".";
            } else {
                return null;
            }
        }

        List<ExecutiveSupportingMetric> supporting = metricsFromGrouping(grouping, metric);
        String title = chartTitle(metric, dimension);

        return new ExecutiveInsightCard(
                title,
                compression.compressParagraph(takeaway),
                supporting,
                chart,
                takeaway,
                confidenceLabel,
                ""
        );
    }

    private String buildTakeaway(
            MaterializedGrouping grouping,
            AnalyticalDepthResult depth,
            String metric,
            String dimension
    ) {
        if (grouping != null && grouping.rankedEntries() != null && !grouping.rankedEntries().isEmpty()) {
            MaterializedGroupEntry top = grouping.rankedEntries().getFirst();
            MaterializedGroupEntry bottom = grouping.rankedEntries().getLast();
            double spread = bottom.totalValue() > 0
                    ? top.totalValue() / bottom.totalValue() : top.totalValue();
            String leader = human.formatBucketPhrase(top.entityKey());
            String tail = human.formatBucketPhrase(bottom.entityKey());
            if (spread >= 1.5) {
                return String.format(Locale.ROOT,
                        "Trips %s generate ~%s more revenue than trips %s.",
                        leader, metrics.asMultiple(spread), tail);
            }
            return String.format(Locale.ROOT,
                    "Revenue is concentrated in trips %s (%s share).",
                    leader, metrics.asSharePct(top.sharePct()));
        }

        if (depth != null && depth.segmentBuckets() != null && !depth.segmentBuckets().isEmpty()) {
            SegmentBucket top = depth.segmentBuckets().stream()
                    .max((a, b) -> Double.compare(a.totalValue(), b.totalValue()))
                    .orElse(depth.segmentBuckets().getFirst());
            return String.format(Locale.ROOT,
                    "%s peaks in the %s band (%.1f%% of observed volume).",
                    metric,
                    top.bucketLabel().toLowerCase(Locale.ROOT),
                    top.sharePercent());
        }

        if (depth != null && depth.relationships() != null && !depth.relationships().isEmpty()) {
            var rel = depth.relationships().getFirst();
            return String.format(Locale.ROOT,
                    "%s shows a %s relationship with %s (r=%.2f).",
                    metric,
                    rel.direction().toLowerCase(Locale.ROOT),
                    aliases.resolve(rel.dim2()),
                    rel.correlationCoefficient());
        }

        return "";
    }

    private ExecutiveInsightCard presentScalar(
            MaterializedScalar scalar,
            List<com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.StructuralFinding> findings,
            ExecutiveConfidenceLabel confidenceLabel
    ) {
        String takeaway = findings != null && !findings.isEmpty()
                ? compression.compressParagraph(findings.getFirst().findingText())
                : "";
        if (takeaway.isBlank()) {
            if (scalar.sharePct() != null) {
                String segment = scalar.segmentLabel() != null ? scalar.segmentLabel() : "The segment";
                takeaway = String.format(Locale.ROOT,
                        "%s contributes %.1f%% of total %s (%.2f).",
                        segment, scalar.sharePct(), scalar.metricLabel(), scalar.value());
            } else {
                takeaway = String.format(Locale.ROOT,
                        "%s is %.2f.", scalar.metricLabel(), scalar.value());
            }
        }

        List<ExecutiveSupportingMetric> supporting = new ArrayList<>();
        if (scalar.sharePct() != null) {
            supporting.add(new ExecutiveSupportingMetric(
                    "Share of total", String.format(Locale.ROOT, "%.1f", scalar.sharePct()), "%",
                    scalar.segmentLabel() != null ? scalar.segmentLabel() : "filtered segment"));
        }
        supporting.add(new ExecutiveSupportingMetric(
                scalar.metricLabel(),
                String.format(Locale.ROOT, "%.2f", scalar.value()),
                "",
                "aggregate value"));

        return new ExecutiveInsightCard(
                scalar.sharePct() != null ? "Contribution analysis" : "Aggregate result",
                takeaway,
                compression.compressMetrics(supporting),
                null,
                takeaway,
                confidenceLabel,
                "");
    }

    private List<ExecutiveSupportingMetric> metricsFromGrouping(
            MaterializedGrouping grouping, String metric
    ) {
        if (grouping == null || grouping.rankedEntries() == null || grouping.rankedEntries().isEmpty()) {
            return List.of();
        }
        List<ExecutiveSupportingMetric> out = new ArrayList<>();
        MaterializedGroupEntry top = grouping.rankedEntries().getFirst();
        out.add(new ExecutiveSupportingMetric(
                aliases.resolveSegment(top.entityKey()) + " share",
                String.format(Locale.ROOT, "%.1f", top.sharePct()),
                "%",
                "largest band"));
        if (grouping.rankedEntries().size() >= 2) {
            MaterializedGroupEntry bottom = grouping.rankedEntries().getLast();
            double spread = bottom.totalValue() > 0 ? top.totalValue() / bottom.totalValue() : 0;
            out.add(new ExecutiveSupportingMetric(
                    "High vs low band",
                    String.format(Locale.ROOT, "%.1fx", spread),
                    "x",
                    "spread across bands"));
        }
        return out.stream().limit(AnswerCompressionPolicy.MAX_METRICS).toList();
    }

    private ChartSpec chartFromGrouping(MaterializedGrouping grouping, String metric, String dimension) {
        if (grouping == null || grouping.rankedEntries() == null || grouping.rankedEntries().isEmpty()) {
            return null;
        }
        List<Map<String, Object>> data = new ArrayList<>();
        int limit = Math.min(8, grouping.rankedEntries().size());
        for (int i = 0; i < limit; i++) {
            MaterializedGroupEntry e = grouping.rankedEntries().get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("segment", human.formatBucketLabel(e.entityKey()));
            row.put("value", e.totalValue());
            data.add(row);
        }
        return new ChartSpec(
                ChartSpec.ChartType.BAR,
                chartTitle(metric, dimension),
                null, "segment", "value",
                null, null, "number", "category", data);
    }

    private ChartSpec chartFromDepth(AnalyticalDepthResult depth) {
        if (depth == null || depth.segmentBuckets() == null || depth.segmentBuckets().isEmpty()) {
            return null;
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (SegmentBucket b : depth.segmentBuckets()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", b.bucketLabel());
            row.put("total", b.totalValue());
            data.add(row);
        }
        return new ChartSpec(
                ChartSpec.ChartType.BAR,
                "Segment distribution",
                null, "bucket", "total",
                null, null, "number", "category", data);
    }

    private String resolveMetric(InvestigationPlan plan, ExecutionFindings execution) {
        if (execution != null && execution.materializedResult() != null
                && execution.materializedResult().valueMetricLabel() != null) {
            return aliases.resolve(execution.materializedResult().valueMetricLabel());
        }
        if (plan != null && plan.reasoningPlan() != null) {
            return aliases.resolve(plan.reasoningPlan().primaryMetric());
        }
        return "Revenue";
    }

    private String chartTitle(String metric, String dimension) {
        String dim = dimension != null ? dimension.toLowerCase(Locale.ROOT) : "";
        if (dim.contains("distance")) return metric + " by Trip Distance";
        if (dim.contains("hour") || dim.contains("time")) return metric + " by Hour";
        if (dim.contains("zone") || dim.contains("location")) return metric + " by Pickup Zone";
        return metric + " by " + dimension;
    }

    private String resolveDimension(
            InvestigationPlan plan,
            ResolvedAnalyticalQuestion resolved,
            MaterializedGrouping grouping
    ) {
        if (grouping != null && grouping.spec() != null && grouping.spec().displayLabel() != null) {
            return grouping.spec().displayLabel();
        }
        if (resolved != null && resolved.assumption() != null && resolved.assumption().grouping() != null) {
            return aliases.resolve(resolved.assumption().grouping());
        }
        if (plan != null && plan.reasoningPlan() != null) {
            return plan.reasoningPlan().groupingDimension();
        }
        return "segment";
    }
}
