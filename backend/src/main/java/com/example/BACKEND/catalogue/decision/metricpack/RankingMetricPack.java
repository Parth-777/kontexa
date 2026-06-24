package com.example.BACKEND.catalogue.decision.metricpack;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles RANKING_ANALYSIS objectives.
 *
 * Generates per-entity TOP-N queries for every numeric metric in the bundle,
 * grouped by the first available categorical dimension.
 *
 * No domain-specific assumptions — operates on whatever the registry provides.
 */
@Component
public class RankingMetricPack implements MetricPack {

    private static final int TOP_N = 20;

    @Override
    public String packKey() { return "RANKING_PACK"; }

    @Override
    public boolean supports(String objectiveKey) {
        return "RANKING_ANALYSIS".equals(objectiveKey);
    }

    @Override
    public List<QuerySpec> buildQuerySpecs(RegistryResolutionBundle bundle, IntentResolution intent) {
        List<QuerySpec> specs = new ArrayList<>();

        for (EntityDescriptor entity : bundle.entities()) {
            // Find a categorical dimension for this entity's table
            String groupBy = firstCategoricalDim(bundle.dimensions(), entity.tableRef());
            if (groupBy == null) continue;

            for (MetricDescriptor metric : bundle.metrics()) {
                if (!metric.key().startsWith(entity.tableRef() + ".")) continue;

                String sql = buildRankingSql(entity.tableRef(), groupBy, metric, TOP_N);
                String key = "ranking__" + entity.key() + "__" + metric.key().replace(".", "_");
                specs.add(new QuerySpec(key, sql, Map.of()));
            }
        }

        // Fallback: if nothing matched, produce a generic count per entity
        if (specs.isEmpty()) {
            for (EntityDescriptor entity : bundle.entities()) {
                String sql = "SELECT COUNT(*) AS total_count FROM " + entity.tableRef() + " LIMIT 1";
                specs.add(new QuerySpec("count__" + entity.key(), sql, Map.of()));
            }
        }

        return specs;
    }

    private String buildRankingSql(String table, String groupByCol, MetricDescriptor metric, int topN) {
        String colOnly = groupByCol.contains(".") ? groupByCol.split("\\.")[1] : groupByCol;
        String aggExpr = metric.aggregation() + "(" + metric.key().split("\\.")[1] + ")";
        return String.format(
                "SELECT %s, %s AS metric_value FROM %s GROUP BY %s ORDER BY metric_value DESC LIMIT %d",
                colOnly, aggExpr, table, colOnly, topN
        );
    }

    private String firstCategoricalDim(List<DimensionDescriptor> dims, String tableRef) {
        return dims.stream()
                .filter(d -> "CATEGORICAL".equals(d.type()) && d.expression().startsWith(tableRef + "."))
                .map(DimensionDescriptor::expression)
                .findFirst()
                .orElse(null);
    }
}
