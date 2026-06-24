package com.example.BACKEND.catalogue.decision.presentation.executive;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured presentation artifact derived deterministically from canonical warehouse rows.
 */
public record ExecutivePresentation(
        String type,
        List<KpiCard> kpis,
        PresentationTable table,
        List<ChartHint> charts,
        PresentationSummary summary,
        List<String> insights,
        List<String> recommendations,
        List<String> followUps,
        Map<String, Object> statistics
) {
    public record KpiCard(
            String label,
            String value,
            String formattedValue,
            String unit
    ) {}

    public record PresentationColumn(String key, String label, String format) {}

    public record PresentationTable(
            String title,
            List<PresentationColumn> columns,
            List<Map<String, String>> rows
    ) {
        public boolean hasContent() {
            return columns != null && !columns.isEmpty()
                    && rows != null && !rows.isEmpty();
        }
    }

    public record ChartHint(
            String chartType,
            String title,
            String categoryKey,
            String valueKey,
            String xKey,
            String yKey,
            String valueFormat
    ) {}

    public record PresentationSummary(
            String presentationType,
            int rowCount,
            int displayedRowCount,
            String primaryMetricLabel,
            String partitionLabel,
            String tableTitle,
            String recommendedChart,
            List<String> highlights
    ) {}

    public static ExecutivePresentation empty(String type) {
        return ExecutivePresentationFactory.create(
                type != null ? type : "SCALAR",
                List.of(),
                new PresentationTable("", List.of(), List.of()),
                List.of(),
                new PresentationSummary(type, 0, 0, "", "", "", "NONE", List.of()));
    }

    public boolean hasStatistics() {
        return statistics != null && !statistics.isEmpty();
    }

    public boolean hasContent() {
        return !kpis.isEmpty() || table.hasContent() || !charts.isEmpty() || !insights.isEmpty();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("kpis", kpis.stream().map(k -> Map.of(
                "label", k.label(),
                "value", k.value(),
                "formatted_value", k.formattedValue(),
                "unit", k.unit() != null ? k.unit() : ""
        )).toList());
        if (table.hasContent()) {
            m.put("table", Map.of(
                    "title", table.title(),
                    "columns", table.columns().stream().map(c -> Map.of(
                            "key", c.key(),
                            "label", c.label(),
                            "format", c.format() != null ? c.format() : "text"
                    )).toList(),
                    "rows", table.rows()
            ));
        } else {
            m.put("table", Map.of("title", "", "columns", List.of(), "rows", List.of()));
        }
        m.put("charts", charts.stream().map(c -> {
            Map<String, Object> chart = new LinkedHashMap<>();
            chart.put("chart_type", c.chartType());
            chart.put("title", c.title() != null ? c.title() : "");
            if (c.categoryKey() != null) chart.put("category_key", c.categoryKey());
            if (c.valueKey() != null) chart.put("value_key", c.valueKey());
            if (c.xKey() != null) chart.put("x_key", c.xKey());
            if (c.yKey() != null) chart.put("y_key", c.yKey());
            if (c.valueFormat() != null) chart.put("value_format", c.valueFormat());
            return chart;
        }).toList());
        m.put("summary", Map.of(
                "presentation_type", summary.presentationType(),
                "row_count", summary.rowCount(),
                "displayed_row_count", summary.displayedRowCount(),
                "primary_metric_label", summary.primaryMetricLabel(),
                "partition_label", summary.partitionLabel() != null ? summary.partitionLabel() : "",
                "table_title", summary.tableTitle() != null ? summary.tableTitle() : "",
                "recommended_chart", summary.recommendedChart(),
                "highlights", summary.highlights()
        ));
        if (insights != null && !insights.isEmpty()) {
            m.put("insights", insights);
        }
        if (recommendations != null && !recommendations.isEmpty()) {
            m.put("recommendations", recommendations);
        }
        if (followUps != null && !followUps.isEmpty()) {
            m.put("follow_ups", followUps);
        }
        if (statistics != null && !statistics.isEmpty()) {
            m.put("statistics", statistics);
        }
        return PresentationValueSanitizer.sanitizePresentationMap(m);
    }
}
