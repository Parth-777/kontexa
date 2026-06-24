package com.example.BACKEND.catalogue.decision.presentation;

import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedGroupEntry;
import com.example.BACKEND.catalogue.decision.presentation.executive.BusinessSemanticAliases;
import com.example.BACKEND.catalogue.decision.presentation.executive.HumanNarrativeFormatter;
import com.example.BACKEND.catalogue.decision.presentation.executive.SemanticMetricFormatter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link TableSpec} from materialized grouped query output.
 */
@Component
public class TableSpecBuilder {

    private final HumanNarrativeFormatter human;
    private final BusinessSemanticAliases aliases;
    private final SemanticMetricFormatter metrics;

    public TableSpecBuilder(
            HumanNarrativeFormatter human,
            BusinessSemanticAliases aliases,
            SemanticMetricFormatter metrics
    ) {
        this.human = human;
        this.aliases = aliases;
        this.metrics = metrics;
    }

    public TableSpec build(ExecutionFindings findings, String title) {
        if (findings == null || findings.materializedResult() == null
                || findings.materializedResult().primaryGrouping() == null) {
            return TableSpec.empty();
        }
        var grouping = findings.materializedResult().primaryGrouping();
        if (grouping.rankedEntries() == null || grouping.rankedEntries().isEmpty()) {
            return TableSpec.empty();
        }

        String dimLabel = grouping.spec() != null && grouping.spec().displayLabel() != null
                ? grouping.spec().displayLabel() : "Segment";
        String metricLabel = findings.materializedResult().valueMetricLabel() != null
                ? aliases.resolve(findings.materializedResult().valueMetricLabel()) : "Value";

        List<TableSpec.Column> columns = List.of(
                new TableSpec.Column("segment", dimLabel, "text"),
                new TableSpec.Column("value", metricLabel, "currency"),
                new TableSpec.Column("share_pct", "Share", "percent"),
                new TableSpec.Column("rank", "Rank", "number")
        );

        List<Map<String, Object>> rows = new ArrayList<>();
        for (MaterializedGroupEntry e : grouping.rankedEntries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("segment", human.formatBucketLabel(e.entityKey()));
            row.put("value", e.totalValue());
            row.put("value_formatted", metrics.formatValue(e.totalValue(),
                    findings.materializedResult().valueMetricLabel()));
            row.put("share_pct", e.sharePct());
            row.put("share_formatted", metrics.asSharePct(e.sharePct()));
            row.put("rank", e.rank());
            rows.add(row);
        }

        return new TableSpec(columns, rows, title != null ? title : metricLabel + " by " + dimLabel,
                true, true);
    }
}
