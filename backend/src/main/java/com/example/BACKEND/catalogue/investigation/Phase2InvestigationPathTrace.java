package com.example.BACKEND.catalogue.investigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request-scoped (ThreadLocal) Phase 2 investigation execution trace.
 *
 * Captures the full INVESTIGATION path for production debugging:
 * plan → resolved metric/dimension → SQL → BigQuery errors → runtime exceptions.
 * Does not change product behaviour — observatory only.
 */
public final class Phase2InvestigationPathTrace {

    private static final Logger log = LoggerFactory.getLogger(Phase2InvestigationPathTrace.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final ThreadLocal<Phase2InvestigationPathTrace> CURRENT = new ThreadLocal<>();

    private final Map<String, Object> data = new LinkedHashMap<>();
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private final List<Map<String, Object>> evidenceRequests = new ArrayList<>();
    private final List<Map<String, Object>> compiledSql = new ArrayList<>();
    private final List<Map<String, Object>> bigQueryErrors = new ArrayList<>();

    private Phase2InvestigationPathTrace(String runId, String question, String tenantId) {
        data.put("run_id", runId);
        data.put("question", question);
        data.put("tenant_id", tenantId);
        data.put("path", "INVESTIGATION");
        data.put("steps", steps);
        data.put("evidence_requests", evidenceRequests);
        data.put("compiled_sql", compiledSql);
        data.put("bigquery_errors", bigQueryErrors);
    }

    public static Phase2InvestigationPathTrace begin(String runId, String question, String tenantId) {
        Phase2InvestigationPathTrace t = new Phase2InvestigationPathTrace(runId, question, tenantId);
        CURRENT.set(t);
        t.step("begin", "Phase 2 investigation path started");
        return t;
    }

    public static Phase2InvestigationPathTrace current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public Phase2InvestigationPathTrace step(String name, String detail) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("step", name);
        s.put("detail", detail);
        s.put("at_ms", System.currentTimeMillis());
        steps.add(s);
        log.info("[phase2-trace] run={} step={} detail={}", data.get("run_id"), name, detail);
        return this;
    }

    public void put(String key, Object value) {
        data.put(key, value);
    }

    public void setInvestigationPlan(Object plan) {
        data.put("investigation_plan", plan);
        step("investigation_plan", "InvestigationPlan emitted");
    }

    public void setResolvedMetric(String metric) {
        data.put("resolved_metric", metric);
        step("resolved_metric", metric != null ? metric : "(null)");
    }

    public void setResolvedDimension(String dimension) {
        data.put("resolved_dimension", dimension);
        step("resolved_dimension", dimension != null ? dimension : "(null)");
    }

    public void addEvidenceRequest(Map<String, Object> request) {
        evidenceRequests.add(request);
    }

    public void addCompiledSql(String requestId, String kind, boolean ok, String sql, String error) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("request_id", requestId);
        row.put("kind", kind);
        row.put("ok", ok);
        if (sql != null) {
            row.put("sql", sql);
        }
        if (error != null) {
            row.put("compile_error", error);
        }
        compiledSql.add(row);
        step("compiled_sql", requestId + " ok=" + ok + (ok ? "" : " error=" + error));
    }

    public void addBigQueryError(String queryKey, String sql, String error) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("query_key", queryKey);
        row.put("sql", sql);
        row.put("error", error);
        bigQueryErrors.add(row);
        data.put("bigquery_error", error);
        step("bigquery_error", queryKey + ": " + error);
    }

    public void setGeneratedSql(String sqlJoined) {
        data.put("generated_sql", sqlJoined != null ? sqlJoined : "");
        step("generated_sql", "len=" + (sqlJoined != null ? sqlJoined.length() : 0));
    }

    public void setException(Throwable ex) {
        if (ex == null) {
            return;
        }
        data.put("runtime_exception_class", ex.getClass().getName());
        data.put("runtime_exception_message", ex.getMessage());
        data.put("runtime_exception_stack_trace", stackTrace(ex));
        data.put("root_cause_class", rootCause(ex).getClass().getName());
        data.put("root_cause_message", rootCause(ex).getMessage());
        step("runtime_exception", ex.getClass().getSimpleName() + ": " + ex.getMessage());
    }

    public void setRootCauseSummary(String summary) {
        data.put("exact_root_cause", summary);
        step("exact_root_cause", summary);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>(data);
        out.put("steps", List.copyOf(steps));
        out.put("evidence_requests", List.copyOf(evidenceRequests));
        out.put("compiled_sql", List.copyOf(compiledSql));
        out.put("bigquery_errors", List.copyOf(bigQueryErrors));
        return out;
    }

    public Path writeToFile(String filenameHint) {
        try {
            String safe = (filenameHint != null ? filenameHint : "phase2")
                    .replaceAll("[^A-Za-z0-9._-]+", "_");
            if (safe.length() > 80) {
                safe = safe.substring(0, 80);
            }
            Path path = Path.of("target", "phase2-investigation-traces", safe + ".json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, JSON.writeValueAsString(snapshot()));
            log.info("[phase2-trace] wrote {}", path.toAbsolutePath());
            data.put("trace_file", path.toAbsolutePath().toString());
            return path;
        } catch (Exception e) {
            log.warn("[phase2-trace] failed to write file: {}", e.getMessage());
            return null;
        }
    }

    public void logSummary() {
        try {
            log.info("[phase2-trace] SUMMARY run={} payload=\n{}",
                    data.get("run_id"), JSON.writeValueAsString(snapshot()));
        } catch (Exception e) {
            log.warn("[phase2-trace] summary serialize failed: {}", e.getMessage());
        }
    }

    private static Throwable rootCause(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static String stackTrace(Throwable ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
