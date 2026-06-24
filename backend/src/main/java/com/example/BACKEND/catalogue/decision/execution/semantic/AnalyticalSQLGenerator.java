package com.example.BACKEND.catalogue.decision.execution.semantic;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.execution.semantic.AnalyticalExecutionPlan.ComputationStep;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts {@link AnalyticalExecutionPlan} steps into warehouse-native {@link QuerySpec}s.
 *
 * This is the ONLY place in the runtime that produces SQL.
 * It never receives the raw user question — only structured computation steps.
 *
 * SQL patterns produced (all generic, no domain logic):
 *
 *   TEMPORAL_GROUPED_RANKING / ENTITY_GROUPED_RANKING:
 *     SELECT {group}, {AGG(metric)} FROM {table}
 *     GROUP BY {group}
 *     ORDER BY {metric_alias} DESC
 *     LIMIT {n}
 *
 *   CONTRIBUTION_ANALYSIS:
 *     SELECT {group}, {AGG(metric)},
 *            ROUND(100.0 * {metric} / NULLIF(SUM({metric}) OVER(), 0), 2) AS share_pct
 *     FROM {table}
 *     GROUP BY {group}
 *     ORDER BY {metric_alias} DESC
 *     LIMIT {n}
 *
 *   EFFICIENCY_RANKING:
 *     SELECT {group}, {AGG(val)}, {AGG(vol)},
 *            ROUND(val/NULLIF(vol,0), 4) AS efficiency_ratio
 *     FROM {table}
 *     GROUP BY {group}
 *     ORDER BY efficiency_ratio DESC
 *     LIMIT {n}
 *
 *   TREND_TIMESERIES:
 *     SELECT {time_expr} AS time_period, {AGG(metric)}
 *     FROM {table}
 *     GROUP BY {time_expr}
 *     ORDER BY time_period ASC
 *
 *   GENERAL_SUMMARY:
 *     SELECT {AGG(metric)} FROM {table}
 */
@Component
public class AnalyticalSQLGenerator {

    public List<QuerySpec> generate(AnalyticalExecutionPlan plan) {
        return plan.steps().stream()
                .map(step -> toQuerySpec(step, plan.tableRef()))
                .collect(Collectors.toList());
    }

    private QuerySpec toQuerySpec(ComputationStep step, String defaultTable) {
        String table = step.tableRef() != null && !step.tableRef().isBlank()
                ? step.tableRef() : defaultTable;

        String sql = buildSql(step, table);
        return new QuerySpec("saee__" + step.stepKey(), sql, Map.of());
    }

    private String buildSql(ComputationStep step, String table) {
        StringBuilder sb = new StringBuilder();

        // SELECT clause
        sb.append("SELECT ");
        sb.append(String.join(", ", step.selectExpressions()));
        sb.append("\nFROM ").append(table);

        // GROUP BY clause
        if (!step.groupByExpressions().isEmpty()) {
            sb.append("\nGROUP BY ");
            // Use positional references for group-by to handle complex expressions
            List<Integer> positions = buildGroupByPositions(step);
            if (positions.isEmpty()) {
                sb.append(String.join(", ", step.groupByExpressions()));
            } else {
                sb.append(positions.stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(", ")));
            }
        }

        // ORDER BY clause
        if (step.orderByClause() != null && !step.orderByClause().isBlank()) {
            sb.append("\nORDER BY ").append(step.orderByClause());
        }

        // LIMIT clause
        if (step.limitClause() > 0) {
            sb.append("\nLIMIT ").append(step.limitClause());
        }

        return sb.toString();
    }

    /**
     * For GROUP BY positional references, we use the position of each groupBy expression
     * in the SELECT list.  This safely handles EXTRACT(), DATE_TRUNC() and other derived
     * expressions that would break if repeated verbatim in GROUP BY.
     *
     * Returns an empty list if positional matching fails (fallback to expression form).
     */
    private List<Integer> buildGroupByPositions(ComputationStep step) {
        List<Integer> positions = new java.util.ArrayList<>();
        for (String gbExpr : step.groupByExpressions()) {
            boolean found = false;
            for (int i = 0; i < step.selectExpressions().size(); i++) {
                String selExpr = step.selectExpressions().get(i);
                // Match if the select expression starts with or equals the group-by expression
                String selBase = selExpr.contains(" AS ")
                        ? selExpr.substring(0, selExpr.lastIndexOf(" AS ")).trim()
                        : selExpr.trim();
                if (selBase.equalsIgnoreCase(gbExpr.trim())) {
                    positions.add(i + 1);  // SQL positions are 1-based
                    found = true;
                    break;
                }
            }
            if (!found) return List.of(); // positional matching failed, fall back
        }
        return positions;
    }
}
