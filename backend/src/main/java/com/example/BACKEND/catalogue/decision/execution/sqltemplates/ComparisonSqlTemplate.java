package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import org.springframework.stereotype.Component;

@Component
public class ComparisonSqlTemplate {

    private final GroupedMetricSqlBuilder sqlBuilder;

    public ComparisonSqlTemplate(GroupedMetricSqlBuilder sqlBuilder) {
        this.sqlBuilder = sqlBuilder;
    }

    public String render(TemplateContext ctx) {
        String compareExpr = ctx.bucketExpression() != null && !ctx.bucketExpression().isBlank()
                ? ctx.bucketExpression()
                : DimensionBucketingSql.weekendFlag(
                        ctx.dimensionColumn() != null ? ctx.dimensionColumn()
                                : HardMetricMappings.TIME_DIMENSION);
        String alias = ctx.bucketAlias() != null ? ctx.bucketAlias() : "segment";
        TemplateContext adjusted = new TemplateContext(
                ctx.question(), AnalyticalIntentKind.COMPARISON,
                ctx.tableRef(), ctx.revenueMetric(), ctx.dimensionColumn(),
                compareExpr, alias, ctx.candidateId());
        return sqlBuilder.renderGroupedQuery(adjusted);
    }
}
