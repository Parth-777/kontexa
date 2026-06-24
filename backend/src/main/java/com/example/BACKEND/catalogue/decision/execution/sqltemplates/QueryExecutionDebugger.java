package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Logs every analytical query execution for debugging hidden SQL failures.
 */
@Component
public class QueryExecutionDebugger {

    private static final Logger log = LoggerFactory.getLogger(QueryExecutionDebugger.class);

    public record ExecutionRecord(
            String  candidateId,
            String  queryKey,
            String  sql,
            int     rowCount,
            boolean success,
            long    elapsedMs,
            String  metric,
            String  dimension,
            String  error
    ) {}

    public void log(ExecutionRecord record) {
        log(record, null);
    }

    public void log(ExecutionRecord record, java.util.List<java.util.Map<String, Object>> rows) {
        if (record.success()) {
            log.info("[sql-debug] key={} candidate={} metric={} dimension={} rows={} ms={} sql={}",
                    record.queryKey(),
                    record.candidateId() != null ? record.candidateId() : "-",
                    record.metric(),
                    record.dimension(),
                    record.rowCount(),
                    record.elapsedMs(),
                    truncate(record.sql()));
            if (rows != null && !rows.isEmpty()) {
                log.info("[sql-debug] key={} sample_rows={}", record.queryKey(), sampleRows(rows));
            }
        } else {
            log.warn("[sql-debug] FAILED key={} candidate={} metric={} dimension={} ms={} error={} sql={}",
                    record.queryKey(),
                    record.candidateId() != null ? record.candidateId() : "-",
                    record.metric(),
                    record.dimension(),
                    record.elapsedMs(),
                    record.error(),
                    truncate(record.sql()));
        }
    }

    public static java.util.List<java.util.Map<String, Object>> firstRows(
            java.util.List<java.util.Map<String, Object>> rows, int limit
    ) {
        if (rows == null || rows.isEmpty()) return java.util.List.of();
        return rows.subList(0, Math.min(limit, rows.size()));
    }

    private String sampleRows(java.util.List<java.util.Map<String, Object>> rows) {
        return firstRows(rows, 20).toString();
    }

    private String truncate(String sql) {
        if (sql == null) return "";
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 500 ? oneLine.substring(0, 500) + "..." : oneLine;
    }
}
