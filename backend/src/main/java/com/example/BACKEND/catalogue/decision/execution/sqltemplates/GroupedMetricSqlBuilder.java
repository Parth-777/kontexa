package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import org.springframework.stereotype.Component;

/**
 * Builds grouped SELECT fragments from intent-driven aggregation specs.
 */
@Component
public class GroupedMetricSqlBuilder {

    private final IntentAggregationStrategy strategy;

    public GroupedMetricSqlBuilder(IntentAggregationStrategy strategy) {
        this.strategy = strategy;
    }

    public String renderGroupedQuery(TemplateContext ctx) {
        AggregationSpec spec = resolveAggregationSpec(ctx);
        return renderGroupedQuery(ctx, spec);
    }

    public String renderGroupedQuery(TemplateContext ctx, AggregationSpec spec) {
        String bucketExpr = ctx.bucketExpression();
        String bucketAlias = ctx.bucketAlias();
        String metric = ctx.revenueMetric();
        String table = ctx.tableRef();

        String sql = switch (spec.aggregation()) {
            case SUM -> sumQuery(bucketExpr, bucketAlias, metric, table, spec);
            case AVG -> """
                    SELECT
                      %s AS %s,
                      AVG(%s) AS %s
                    FROM %s
                    GROUP BY %s
                    ORDER BY %s DESC""".formatted(
                    bucketExpr, bucketAlias, metric, spec.valueAlias(),
                    table, bucketAlias, spec.valueAlias());
            case COUNT -> """
                    SELECT
                      %s AS %s,
                      COUNT(*) AS %s,
                      ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2) AS share_pct
                    FROM %s
                    GROUP BY %s
                    ORDER BY %s DESC""".formatted(
                    bucketExpr, bucketAlias, spec.valueAlias(),
                    table, bucketAlias, spec.valueAlias());
            case RATIO -> """
                    SELECT
                      %s AS %s,
                      SAFE_DIVIDE(SUM(%s), NULLIF(COUNT(*), 0)) AS %s
                    FROM %s
                    GROUP BY %s
                    ORDER BY %s DESC""".formatted(
                    bucketExpr, bucketAlias, metric, spec.valueAlias(),
                    table, bucketAlias, spec.valueAlias());
        };
        return SqlRenderPostProcessor.apply(sql, ctx.renderHints());
    }

    private AggregationSpec resolveAggregationSpec(TemplateContext ctx) {
        if (ctx.renderHints() != null) {
            AggregationSpec hinted = ctx.renderHints().resolveAggregation(ctx.intent(), ctx.revenueMetric());
            if (hinted != null) {
                return hinted;
            }
        }
        return strategy.forSqlIntent(ctx.intent(), ctx.revenueMetric());
    }

    private String sumQuery(
            String bucketExpr, String bucketAlias, String metric, String table, AggregationSpec spec
    ) {
        if (spec.includeSharePercent()) {
            return """
                    SELECT
                      %s AS %s,
                      SUM(%s) AS %s,
                      ROUND(100.0 * SUM(%s) / NULLIF(SUM(SUM(%s)) OVER (), 0), 2) AS share_pct
                    FROM %s
                    GROUP BY %s
                    ORDER BY %s DESC""".formatted(
                    bucketExpr, bucketAlias,
                    metric, spec.valueAlias(),
                    metric, metric,
                    table, bucketAlias, spec.valueAlias());
        }
        return """
                SELECT
                  %s AS %s,
                  SUM(%s) AS %s
                FROM %s
                GROUP BY %s
                ORDER BY %s DESC""".formatted(
                bucketExpr, bucketAlias, metric, spec.valueAlias(),
                table, bucketAlias, spec.valueAlias());
    }
}
