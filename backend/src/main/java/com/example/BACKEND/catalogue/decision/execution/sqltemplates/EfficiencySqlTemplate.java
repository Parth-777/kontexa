package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import org.springframework.stereotype.Component;

/**
 * EFFICIENCY: AVG(metric) per segment — only for per-unit / efficiency questions.
 */
@Component
public class EfficiencySqlTemplate {

    private final GroupedMetricSqlBuilder sqlBuilder;

    public EfficiencySqlTemplate(GroupedMetricSqlBuilder sqlBuilder) {
        this.sqlBuilder = sqlBuilder;
    }

    public String render(TemplateContext ctx) {
        return sqlBuilder.renderGroupedQuery(ctx);
    }
}
