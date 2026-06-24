package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import org.springframework.stereotype.Component;

@Component
public class TrendSqlTemplate {

    private final GroupedMetricSqlBuilder sqlBuilder;

    public TrendSqlTemplate(GroupedMetricSqlBuilder sqlBuilder) {
        this.sqlBuilder = sqlBuilder;
    }

    public String render(TemplateContext ctx) {
        String dimension = ctx.dimensionColumn() != null && !ctx.dimensionColumn().isBlank()
                ? ctx.dimensionColumn() : "time_period";
        String timeExpr = ctx.bucketExpression() != null && !ctx.bucketExpression().isBlank()
                ? ctx.bucketExpression()
                : dimension;
        String alias = ctx.bucketAlias() != null ? ctx.bucketAlias() : "time_period";
        TemplateContext adjusted = new TemplateContext(
                ctx.question(), AnalyticalIntentKind.TREND,
                ctx.tableRef(), ctx.revenueMetric(), ctx.dimensionColumn(),
                timeExpr, alias, ctx.candidateId(),
                ctx.relationshipVariable(), ctx.renderHints());
        return sqlBuilder.renderGroupedQuery(adjusted);
    }
}
