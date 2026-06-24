package com.example.BACKEND.catalogue.decision.exploration;

import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedGroupEntry;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse.FindingItem;
import com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse.MetricItem;
import com.example.BACKEND.catalogue.decision.presentation.executive.BusinessSemanticAliases;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds provisional findings and metrics from grouped aggregates when formal
 * governance filtered structured findings — used in hybrid/exploratory modes.
 */
@Component
public class ProvisionalFindingBuilder {

    private final BusinessSemanticAliases aliases;

    public ProvisionalFindingBuilder(BusinessSemanticAliases aliases) {
        this.aliases = aliases;
    }

    public record ProvisionalResult(List<FindingItem> findings, List<MetricItem> metrics) {
        public static ProvisionalResult empty() {
            return new ProvisionalResult(List.of(), List.of());
        }

        public boolean hasContent() {
            return !findings.isEmpty() || !metrics.isEmpty();
        }
    }

    public ProvisionalResult build(ExecutionFindings execution, String metricLabel, String dimensionLabel) {
        if (execution == null || execution.materializedResult() == null) {
            return ProvisionalResult.empty();
        }
        MaterializedGrouping grouping = execution.materializedResult().primaryGrouping();
        if (grouping == null || grouping.rankedEntries() == null || grouping.rankedEntries().isEmpty()) {
            return ProvisionalResult.empty();
        }

        String metric = metricLabel != null ? aliases.resolve(metricLabel) : "Revenue";
        String dimension = dimensionLabel != null ? aliases.resolve(dimensionLabel) : "segment";

        List<FindingItem> findings = new ArrayList<>();
        List<MetricItem> metrics = new ArrayList<>();

        MaterializedGroupEntry top = grouping.rankedEntries().getFirst();
        findings.add(new FindingItem(
                "PROVISIONAL_CONTRIBUTION",
                metric + " · " + dimension,
                String.format(Locale.ROOT,
                        "%s leads among %s groups at %.1f%% share.",
                        aliases.resolveSegment(top.entityKey()), dimension.toLowerCase(Locale.ROOT),
                        top.sharePct()),
                top.sharePct() / 100.0
        ));

        if (grouping.rankedEntries().size() >= 2) {
            MaterializedGroupEntry bottom = grouping.rankedEntries().getLast();
            double spread = bottom.totalValue() > 0 ? top.totalValue() / bottom.totalValue() : 0;
            findings.add(new FindingItem(
                    "PROVISIONAL_RANKING",
                    metric + " spread",
                    String.format(Locale.ROOT,
                            "Highest band is %.1fx the lowest across %d groups.",
                            spread, grouping.groupCount()),
                    spread
            ));
            metrics.add(new MetricItem("spread", "High vs low band",
                    String.format(Locale.ROOT, "%.1fx", spread), "x", "", ""));
        }

        metrics.add(new MetricItem("top_share",
                aliases.resolveSegment(top.entityKey()) + " share",
                String.format(Locale.ROOT, "%.1f", top.sharePct()), "%", "", ""));

        return new ProvisionalResult(findings, metrics.stream().limit(2).toList());
    }

    public boolean hasGroupedData(ExecutionFindings execution) {
        if (execution == null || execution.materializedResult() == null) return false;
        MaterializedQueryResult mat = execution.materializedResult();
        return mat.totalRows() > 0
                && mat.primaryGrouping() != null
                && mat.primaryGrouping().rankedEntries() != null
                && !mat.primaryGrouping().rankedEntries().isEmpty();
    }
}
