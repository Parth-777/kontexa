package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import org.springframework.stereotype.Component;

/**
 * CONTRIBUTION: SUM(metric) GROUP BY dimension with optional share %.
 */
@Component
public class ContributionSqlTemplate {

    private final GroupedMetricSqlBuilder sqlBuilder;

    public ContributionSqlTemplate(GroupedMetricSqlBuilder sqlBuilder) {
        this.sqlBuilder = sqlBuilder;
    }

    public String render(TemplateContext ctx) {
        return sqlBuilder.renderGroupedQuery(ctx);
    }
}
