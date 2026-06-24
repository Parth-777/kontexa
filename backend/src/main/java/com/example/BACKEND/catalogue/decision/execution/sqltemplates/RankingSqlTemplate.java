package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import org.springframework.stereotype.Component;

@Component
public class RankingSqlTemplate {

    private final GroupedMetricSqlBuilder sqlBuilder;

    public RankingSqlTemplate(GroupedMetricSqlBuilder sqlBuilder) {
        this.sqlBuilder = sqlBuilder;
    }

    public String render(TemplateContext ctx) {
        String sql = sqlBuilder.renderGroupedQuery(ctx);
        if (ctx.renderHints() != null && ctx.renderHints().resultLimit() != null) {
            return sql;
        }
        if (sql.toUpperCase().contains("LIMIT")) {
            return sql;
        }
        return sql + "\nLIMIT 10";
    }
}
