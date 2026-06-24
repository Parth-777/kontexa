package com.example.BACKEND.catalogue.decision.execution.repair;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-run collection of execution diagnostics for debug panel and traces.
 */
@Component
public class ExecutionDiagnosticSession {

    private final ConcurrentHashMap<UUID, List<RepairOutcome>> byRun = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, java.util.Map<String, Object>> warehouseFacts = new ConcurrentHashMap<>();

    public void record(UUID runId, RepairOutcome outcome) {
        if (runId == null || outcome == null) return;
        byRun.computeIfAbsent(runId, k -> new ArrayList<>()).add(outcome);
    }

    public List<RepairOutcome> outcomes(UUID runId) {
        return byRun.getOrDefault(runId, List.of());
    }

    public List<ExecutionDiagnostics> allAttempts(UUID runId) {
        return outcomes(runId).stream()
                .flatMap(o -> o.attempts().stream())
                .toList();
    }

    public void recordWarehouseFacts(UUID runId, java.util.Map<String, Object> facts) {
        if (runId == null || facts == null) return;
        warehouseFacts.put(runId, facts);
        int rowCount = facts.get("warehouse_row_count") instanceof Number n ? n.intValue() : 0;
        int sampleSize = facts.get("sample_rows") instanceof List<?> rows ? rows.size() : 0;
        String sql = facts.get("generated_sql") != null ? facts.get("generated_sql").toString() : "";
        org.slf4j.LoggerFactory.getLogger(ExecutionDiagnosticSession.class).info(
                "[warehouse-facts] record runId={} session={} row_count={} sample_rows={} sql_len={}",
                runId, System.identityHashCode(this), rowCount, sampleSize, sql.length());
    }

    public java.util.Map<String, Object> warehouseFacts(UUID runId) {
        java.util.Map<String, Object> facts = warehouseFacts.getOrDefault(runId, java.util.Map.of());
        if (!facts.isEmpty()) {
            int rowCount = facts.get("warehouse_row_count") instanceof Number n ? n.intValue() : 0;
            int sampleSize = facts.get("sample_rows") instanceof List<?> rows ? rows.size() : 0;
            String sql = facts.get("generated_sql") != null ? facts.get("generated_sql").toString() : "";
            org.slf4j.LoggerFactory.getLogger(ExecutionDiagnosticSession.class).info(
                    "[warehouse-facts] read runId={} session={} row_count={} sample_rows={} sql_len={}",
                    runId, System.identityHashCode(this), rowCount, sampleSize, sql.length());
        }
        return facts;
    }

    public void clear(UUID runId) {
        if (runId != null) {
            byRun.remove(runId);
            warehouseFacts.remove(runId);
        }
    }
}
