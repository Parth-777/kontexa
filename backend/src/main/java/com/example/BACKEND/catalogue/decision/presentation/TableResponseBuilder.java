package com.example.BACKEND.catalogue.decision.presentation;

import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedGroupEntry;
import com.example.BACKEND.catalogue.decision.presentation.executive.SemanticMetricFormatter;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Table-first analytical output with formatted percentages, currency, and ranks.
 */
@Component
public class TableResponseBuilder {

    private final BusinessLabelFormatter labels;
    private final SemanticMetricFormatter metrics;

    public TableResponseBuilder(BusinessLabelFormatter labels, SemanticMetricFormatter metrics) {
        this.labels = labels;
        this.metrics = metrics;
    }

    public TableSpec build(ExecutionFindings findings, MetricResolution resolution, String title) {
        if (findings == null || findings.materializedResult() == null
                || findings.materializedResult().primaryGrouping() == null) {
            return TableSpec.empty();
        }
        var grouping = findings.materializedResult().primaryGrouping();
        if (grouping.rankedEntries() == null || grouping.rankedEntries().isEmpty()) {
            return TableSpec.empty();
        }

        String dimLabel = resolution != null && resolution.dimensionLabel() != null
                ? resolution.dimensionLabel()
                : (grouping.spec() != null ? grouping.spec().displayLabel() : "Segment");
        String metricLabel = resolution != null && resolution.primaryMetricLabel() != null
                ? resolution.primaryMetricLabel()
                : findings.materializedResult().valueMetricLabel();

        List<TableSpec.Column> columns = List.of(
                new TableSpec.Column("segment", dimLabel, "text"),
                new TableSpec.Column("value", metricLabel, "currency"),
                new TableSpec.Column("share_pct", "Share", "percent"),
                new TableSpec.Column("rank", "Rank", "number")
        );

        List<Map<String, Object>> rows = new ArrayList<>();
        for (MaterializedGroupEntry e : grouping.rankedEntries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("segment", labels.formatBucket(e.entityKey()));
            row.put("value", e.totalValue());
            row.put("value_formatted", metrics.formatValue(e.totalValue(), metricLabel));
            row.put("share_pct", e.sharePct());
            row.put("share_formatted", metrics.asSharePct(e.sharePct()));
            row.put("rank", e.rank());
            rows.add(row);
        }

        return new TableSpec(columns, rows,
                title != null ? title : metricLabel + " by " + dimLabel,
                true, true);
    }
}
