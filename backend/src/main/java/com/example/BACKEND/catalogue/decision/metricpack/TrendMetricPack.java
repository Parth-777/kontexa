package com.example.BACKEND.catalogue.decision.metricpack;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles TREND_ANALYSIS objectives.
 *
 * Generates monthly time-series queries for numeric metrics, grouped by
 * the first available temporal dimension in the resolved bundle.
 */
@Component
public class TrendMetricPack implements MetricPack {

    @Override
    public String packKey() { return "TREND_PACK"; }

    @Override
    public boolean supports(String objectiveKey) {
        return "TREND_ANALYSIS".equals(objectiveKey) || "ANOMALY_DETECTION".equals(objectiveKey);
    }

    @Override
    public List<QuerySpec> buildQuerySpecs(RegistryResolutionBundle bundle, IntentResolution intent) {
        List<QuerySpec> specs = new ArrayList<>();

        for (EntityDescriptor entity : bundle.entities()) {
            String temporalDim = firstTemporalDim(bundle.dimensions(), entity.tableRef());
            if (temporalDim == null) continue;

            for (MetricDescriptor metric : bundle.metrics()) {
                if (!metric.key().startsWith(entity.tableRef() + ".")) continue;

                String sql = buildTrendSql(entity.tableRef(), temporalDim, metric);
                String key = "trend__" + entity.key() + "__" + metric.key().replace(".", "_");
                specs.add(new QuerySpec(key, sql, Map.of()));
            }
        }

        if (specs.isEmpty()) {
            for (EntityDescriptor entity : bundle.entities()) {
                String sql = "SELECT COUNT(*) AS total_count FROM " + entity.tableRef() + " LIMIT 1";
                specs.add(new QuerySpec("count__" + entity.key(), sql, Map.of()));
            }
        }

        return specs;
    }

    private String buildTrendSql(String table, String temporalExpr, MetricDescriptor metric) {
        String colOnly     = temporalExpr.contains(".") ? temporalExpr.split("\\.")[1] : temporalExpr;
        String metricOnly  = metric.key().split("\\.")[1];
        String aggExpr     = metric.aggregation() + "(" + metricOnly + ")";
        // DATE_TRUNC is BigQuery-safe; adapter layer handles dialect differences
        return String.format(
                "SELECT DATE_TRUNC(%s, MONTH) AS period, %s AS metric_value FROM %s " +
                "GROUP BY period ORDER BY period DESC LIMIT 24",
                colOnly, aggExpr, table
        );
    }

    private String firstTemporalDim(List<DimensionDescriptor> dims, String tableRef) {
        return dims.stream()
                .filter(d -> "TEMPORAL".equals(d.type()) && d.expression().startsWith(tableRef + "."))
                .map(DimensionDescriptor::expression)
                .findFirst()
                .orElse(null);
    }
}
