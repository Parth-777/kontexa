package com.example.BACKEND.catalogue.decision.presentation;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult;
import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult.SegmentBucket;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import com.example.BACKEND.catalogue.decision.findings.FindingType;
import com.example.BACKEND.catalogue.decision.findings.StructuredFindingsBundle;
import com.example.BACKEND.catalogue.decision.presentation.executive.HumanNarrativeFormatter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic chart selection from analytical intent — no LLM required.
 *
 * contribution → bar | composition → donut
 * trend        → line
 * ranking      → horizontal bars
 * distribution → histogram
 * comparison   → grouped bars
 */
@Component
public class VisualizationPlanner {

    private final MetricBucketingEngine bucketing;
    private final HumanNarrativeFormatter human;

    public VisualizationPlanner(MetricBucketingEngine bucketing, HumanNarrativeFormatter human) {
        this.bucketing = bucketing;
        this.human = human;
    }

    public ChartSpec plan(StructuredFindingsBundle bundle, AnalyticalDepthResult depthResult) {
        if (bundle == null || !bundle.hasStructuredFindings()) {
            return planFromDepth(depthResult);
        }

        AnalyticalFinding primary = bundle.allFindings().isEmpty() ? null : bundle.allFindings().getFirst();
        if (primary == null) return planFromDepth(depthResult);
        return planForFinding(primary, depthResult);
    }

    /** Chart grounded to a single finding — narrative and chart share the same discovery. */
    public ChartSpec planForFinding(AnalyticalFinding finding, AnalyticalDepthResult depthResult) {
        if (finding == null) return planFromDepth(depthResult);
        return switch (finding) {
            case ContributionFinding c -> chartContribution(c);
            case RankingFinding r -> chartRanking(r);
            case TemporalPatternFinding t -> chartTemporal(t);
            case ComparativeFinding c -> chartComparative(c);
            case EfficiencyFinding e -> chartEfficiency(e);
            case CorrelationFinding c -> chartCorrelation(c);
        };
    }

    private ChartSpec chartCorrelation(CorrelationFinding f) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("coefficient", f.correlationCoefficient());
        row.put("strength", f.strength());
        row.put("direction", f.direction());
        row.put("source", f.sourceVariable());
        row.put("target", f.targetVariable());
        row.put("sample_size", f.sampleSize());
        return new ChartSpec(
                ChartSpec.ChartType.CORRELATION,
                f.sourceVariable() + " vs " + f.targetVariable(),
                f.executiveSummary(),
                null, null, null, null,
                "number", "category",
                List.of(row));
    }

    private ChartSpec planFromDepth(AnalyticalDepthResult depth) {
        if (depth == null || depth.segmentBuckets() == null || depth.segmentBuckets().isEmpty()) return null;
        return chartHistogram(depth.segmentBuckets());
    }

    private ChartSpec chartContribution(ContributionFinding f) {
        if (f.segments().size() <= 5 && f.topContributorSharePct() > 0) {
            return donutFromSegments(f.dimensionLabel(), f.segments());
        }
        return barFromSegments(f.dimensionLabel(), f.segments(), false);
    }

    private ChartSpec chartRanking(RankingFinding f) {
        List<Map<String, Object>> data = new ArrayList<>();
        int limit = Math.min(8, f.rankedEntities().size());
        for (int i = 0; i < limit; i++) {
            var e = f.rankedEntities().get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entity", e.name());
            row.put("value", e.value());
            data.add(row);
        }
        return spec(
                ChartSpec.ChartType.HBAR,
                human.chartTitle(f),
                "entity", "value",
                null, null,
                "number", "category", data
        );
    }

    private ChartSpec chartTemporal(TemporalPatternFinding f) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (var p : f.periods()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", p.label());
            row.put("value", p.value());
            data.add(row);
        }
        return spec(
                ChartSpec.ChartType.LINE,
                human.chartTitle(f),
                null, null,
                "period", "value",
                "number", "date", data
        );
    }

    private ChartSpec chartComparative(ComparativeFinding f) {
        List<Map<String, Object>> data = new ArrayList<>();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("entity", f.entityA());
        a.put("value", f.valueA());
        data.add(a);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("entity", f.entityB());
        b.put("value", f.valueB());
        data.add(b);

        return spec(
                ChartSpec.ChartType.GROUPED_BAR,
                human.chartTitle(f),
                "entity", "value",
                null, null,
                "number", "category", data
        );
    }

    private ChartSpec chartEfficiency(EfficiencyFinding f) {
        List<Map<String, Object>> data = new ArrayList<>();
        int limit = Math.min(8, f.entries().size());
        for (int i = 0; i < limit; i++) {
            var e = f.entries().get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("entity", e.name());
            row.put("efficiency", e.efficiencyRatio());
            data.add(row);
        }
        return spec(
                ChartSpec.ChartType.BAR,
                human.chartTitle(f),
                "entity", "efficiency",
                null, null,
                "number", "category", data
        );
    }

    private ChartSpec chartHistogram(List<SegmentBucket> buckets) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (var b : buckets) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", b.bucketLabel());
            row.put("count", b.count());
            row.put("total", b.totalValue());
            data.add(row);
        }
        String dim = buckets.getFirst().dimensionKey();
        return spec(
                ChartSpec.ChartType.HISTOGRAM,
                formatTitle(dim) + " distribution",
                "bucket", "count",
                null, null,
                "number", "category", data
        );
    }

    private ChartSpec donutFromSegments(String label, List<ContributionFinding.Segment> segments) {
        List<Map<String, Object>> data = new ArrayList<>();
        int limit = Math.min(6, segments.size());
        for (int i = 0; i < limit; i++) {
            var s = segments.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("segment", s.name());
            row.put("share", s.sharePct());
            data.add(row);
        }
        return spec(
                ChartSpec.ChartType.DONUT,
                chartTitleForDimension(label, segments.getFirst().name()) + " mix",
                "segment", "share",
                null, null,
                "percent", "category", data
        );
    }

    private ChartSpec barFromSegments(String label, List<ContributionFinding.Segment> segments, boolean horizontal) {
        List<Map<String, Object>> data = new ArrayList<>();
        int limit = Math.min(8, segments.size());
        for (int i = 0; i < limit; i++) {
            var s = segments.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("segment", s.name());
            row.put("value", s.value());
            data.add(row);
        }
        return spec(
                horizontal ? ChartSpec.ChartType.HBAR : ChartSpec.ChartType.BAR,
                chartTitleForDimension(label, segments.isEmpty() ? "" : segments.getFirst().name()),
                "segment", "value",
                null, null,
                "number", "category", data
        );
    }

    private ChartSpec spec(
            ChartSpec.ChartType type, String title,
            String categoryKey, String valueKey,
            String xKey, String yKey,
            String valueFormat, String xFormat,
            List<Map<String, Object>> data
    ) {
        if (data == null || data.isEmpty()) return null;
        return new ChartSpec(type, title, null, categoryKey, valueKey, xKey, yKey, valueFormat, xFormat, data);
    }

    private String formatTitle(String raw) {
        if (raw == null || raw.isBlank()) return "Metric";
        return raw.substring(0, 1).toUpperCase(Locale.ROOT) + raw.substring(1);
    }

    private String chartTitleForDimension(String dimensionLabel, String sampleSegment) {
        String dim = formatTitle(dimensionLabel != null ? dimensionLabel : "segment");
        if (dim.toLowerCase(Locale.ROOT).contains("distance")) {
            return "Revenue by Trip Distance";
        }
        if (dim.toLowerCase(Locale.ROOT).contains("hour") || dim.toLowerCase(Locale.ROOT).contains("time")) {
            return "Revenue by Hour";
        }
        if (dim.toLowerCase(Locale.ROOT).contains("zone") || dim.toLowerCase(Locale.ROOT).contains("location")) {
            return "Revenue by Pickup Zone";
        }
        return "Revenue by " + dim;
    }
}
