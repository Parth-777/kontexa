package com.example.BACKEND.catalogue.decision.execution.repair;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captured warehouse execution facts for one query attempt.
 */
public record ExecutionDiagnostics(
        String        queryKey,
        String        strategy,
        int           attemptIndex,
        String        sql,
        long          elapsedMs,
        int           rowCount,
        List<String>  selectedColumns,
        List<String>  groupByColumns,
        String        whereClause,
        List<String>  aggregationExpressions,
        String        warehouseError,
        String        failureReason,
        boolean       success
) {
    public static ExecutionDiagnostics fromExecution(
            String queryKey,
            String strategy,
            int attemptIndex,
            String sql,
            long elapsedMs,
            List<Map<String, Object>> rows,
            String warehouseError
    ) {
        int rowCount = rows != null ? rows.size() : 0;
        String failure = warehouseError != null ? "WAREHOUSE_ERROR"
                : rowCount == 0 ? "ZERO_ROWS" : null;
        return new ExecutionDiagnostics(
                queryKey, strategy, attemptIndex, sql, elapsedMs, rowCount,
                SqlStructureAnalyzer.selectedColumns(sql),
                SqlStructureAnalyzer.groupByColumns(sql),
                SqlStructureAnalyzer.whereClause(sql),
                SqlStructureAnalyzer.aggregationExpressions(sql),
                warehouseError, failure,
                warehouseError == null && rowCount > 0
        );
    }

    public ExecutionDiagnostics withInspection(String issue) {
        if (issue == null || issue.isBlank()) return this;
        return new ExecutionDiagnostics(
                queryKey, strategy, attemptIndex, sql, elapsedMs, rowCount,
                selectedColumns, groupByColumns, whereClause, aggregationExpressions,
                warehouseError, issue, false
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("query_key", queryKey);
        m.put("strategy", strategy);
        m.put("attempt_index", attemptIndex);
        m.put("sql", sql);
        m.put("elapsed_ms", elapsedMs);
        m.put("row_count", rowCount);
        m.put("selected_columns", selectedColumns);
        m.put("group_by_columns", groupByColumns);
        m.put("where_clause", whereClause);
        m.put("aggregation_expressions", aggregationExpressions);
        m.put("warehouse_error", warehouseError != null ? warehouseError : "");
        m.put("failure_reason", failureReason != null ? failureReason : "");
        m.put("success", success);
        return m;
    }
}
