package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import org.springframework.stereotype.Component;

/**
 * DISTRIBUTION: COUNT(*) and percentage — not AVG(metric).
 */
@Component
public class DistributionSqlTemplate {

    private final GroupedMetricSqlBuilder sqlBuilder;

    public DistributionSqlTemplate(GroupedMetricSqlBuilder sqlBuilder) {
        this.sqlBuilder = sqlBuilder;
    }

    public String render(TemplateContext ctx) {
        return sqlBuilder.renderGroupedQuery(ctx);
    }
}
