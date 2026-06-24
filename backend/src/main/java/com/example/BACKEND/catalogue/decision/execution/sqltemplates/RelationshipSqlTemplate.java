package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import org.springframework.stereotype.Component;

/**
 * RELATIONSHIP: Pearson correlation between two numeric columns at row level.
 */
@Component
public class RelationshipSqlTemplate {

    public String render(TemplateContext ctx) {
        String source = ctx.relationshipVariable();
        String target = ctx.revenueMetric();
        if (source == null || source.isBlank() || target == null || target.isBlank()) {
            return "";
        }
        return """
                SELECT
                  CORR(%s, %s) AS correlation_coefficient,
                  COUNT(*) AS row_count
                FROM %s
                WHERE %s IS NOT NULL AND %s IS NOT NULL""".formatted(
                source, target, ctx.tableRef(), source, target);
    }
}
