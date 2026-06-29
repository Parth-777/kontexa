package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import com.example.BACKEND.catalogue.decision.compute.WarehouseExecutor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricPackExecutionPlan;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.execution.repair.ExecutionDiagnosticSession;
import com.example.BACKEND.catalogue.decision.execution.repair.ExecutionDiagnostics;
import com.example.BACKEND.catalogue.decision.execution.repair.RepairOutcome;
import com.example.BACKEND.identity.auth.TenantAccessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Executes template SQL with basic fallback retries.
 * Stabilization mode: accept any non-empty row set — do not block on quality gates.
 */
@Component
public class AnalyticalSqlExecutionService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticalSqlExecutionService.class);

    private final WarehouseExecutor           warehouseExecutor;
    private final SqlFallbackExecutionChain   fallbackChain;
    private final QueryExecutionDebugger      debugger;
    private final ExecutionDiagnosticSession diagnosticSession;

    public AnalyticalSqlExecutionService(
            WarehouseExecutor warehouseExecutor,
            SqlFallbackExecutionChain fallbackChain,
            QueryExecutionDebugger debugger,
            ExecutionDiagnosticSession diagnosticSession
    ) {
        this.warehouseExecutor = warehouseExecutor;
        this.fallbackChain    = fallbackChain;
        this.debugger          = debugger;
        this.diagnosticSession = diagnosticSession;
    }

    public QueryResult executeWithFallbacks(QuerySpec spec, TemplateContext ctx, String tenantId) {
        // Defense in depth: assert tenant BEFORE any execution (outside the
        // per-attempt try/catch so a mismatch aborts instead of being swallowed).
        TenantAccessGuard.assertTenantMatchesAuthContext(tenantId);

        String metric = ctx.revenueMetric();
        String dimension = ctx.dimensionColumn() != null ? ctx.dimensionColumn() : "";
        String candidateId = ctx.candidateId();

        List<String> attempts = new ArrayList<>();
        attempts.add(spec.sql());
        attempts.addAll(fallbackChain.fallbacks(spec.sql(), ctx));

        QueryResult lastResult = null;
        List<ExecutionDiagnostics> diagnostics = new ArrayList<>();

        for (int i = 0; i < attempts.size(); i++) {
            String sql = attempts.get(i);
            String strategy = i == 0 ? "primary" : "fallback_" + i;
            String attemptKey = spec.key() + (i == 0 ? "" : "_fb" + i);
            QuerySpec attempt = new QuerySpec(attemptKey, sql, spec.params());
            long start = System.currentTimeMillis();

            try {
                ComputationResultSet rs = warehouseExecutor.execute(
                        singlePlan(attempt, tenantId), tenantId);
                QueryResult qr = rs.results().isEmpty() ? null : rs.results().getFirst();
                long elapsed = qr != null ? qr.elapsedMs() : System.currentTimeMillis() - start;
                int rowCount = qr != null && qr.rows() != null ? qr.rows().size() : 0;
                boolean success = rowCount > 0;

                diagnostics.add(ExecutionDiagnostics.fromExecution(
                        spec.key(), strategy, i, sql, elapsed,
                        qr != null ? qr.rows() : List.of(), null));

                debugger.log(new QueryExecutionDebugger.ExecutionRecord(
                        candidateId, attemptKey, sql, rowCount, success, elapsed,
                        metric, dimension, success ? null : "zero rows"),
                        qr != null ? qr.rows() : List.of());

                if (success) {
                    if (i > 0) {
                        log.info("[sql-exec] fallback {} succeeded rows={} key={}", i, rowCount, attemptKey);
                    }
                    return new QueryResult(spec.key(), qr.rows(), elapsed);
                }
                lastResult = qr != null ? qr : new QueryResult(spec.key(), List.of(), elapsed);
            } catch (Exception ex) {
                long elapsed = System.currentTimeMillis() - start;
                diagnostics.add(ExecutionDiagnostics.fromExecution(
                        spec.key(), strategy, i, sql, elapsed, List.of(), ex.getMessage()));
                debugger.log(new QueryExecutionDebugger.ExecutionRecord(
                        candidateId, attemptKey, sql, 0, false, elapsed,
                        metric, dimension, ex.getMessage()));
                log.warn("[sql-exec] attempt {} FAILED key={} error={}", i, attemptKey, ex.getMessage());
            }
        }

        log.warn("[sql-exec] all {} fallback paths exhausted for key={}", attempts.size(), spec.key());
        return lastResult != null ? lastResult : new QueryResult(spec.key(), List.of(), 0);
    }

    public List<QueryResult> executeTemplateBatch(
            List<QuerySpec> specs, String question, String tenantId
    ) {
        return executeTemplateBatch(specs, question, tenantId, null);
    }

    public List<QueryResult> executeTemplateBatch(
            List<QuerySpec> specs, String question, String tenantId, UUID runId
    ) {
        // Defense in depth: assert tenant BEFORE the execution loop (outside the
        // per-attempt try/catch so a mismatch aborts instead of being swallowed).
        TenantAccessGuard.assertTenantMatchesAuthContext(tenantId);

        List<QueryResult> results = new ArrayList<>();
        for (QuerySpec spec : specs) {
            if (isTemplateSpec(spec)) {
                TemplateContext ctx = TemplateContext.fromQuerySpec(spec, question);
                List<ExecutionDiagnostics> attempts = new ArrayList<>();
                QueryResult result = executeWithFallbacksAndDiagnostics(spec, ctx, tenantId, attempts);
                if (runId != null) {
                    boolean repaired = attempts.stream().anyMatch(a -> a.success() && a.attemptIndex() > 0);
                    String strategy = attempts.stream().filter(ExecutionDiagnostics::success)
                            .map(ExecutionDiagnostics::strategy).findFirst().orElse("none");
                    diagnosticSession.record(runId, new RepairOutcome(
                            result, strategy, attempts.size(), attempts, List.of(), repaired));
                }
                results.add(result);
            } else {
                ComputationResultSet rs = warehouseExecutor.execute(singlePlan(spec, tenantId), tenantId);
                if (!rs.results().isEmpty()) {
                    results.add(rs.results().getFirst());
                }
            }
        }
        return results;
    }

    private QueryResult executeWithFallbacksAndDiagnostics(
            QuerySpec spec, TemplateContext ctx, String tenantId,
            List<ExecutionDiagnostics> diagnosticsOut
    ) {
        String metric = ctx.revenueMetric();
        String dimension = ctx.dimensionColumn() != null ? ctx.dimensionColumn() : "";
        String candidateId = ctx.candidateId();

        List<String> attempts = new ArrayList<>();
        attempts.add(spec.sql());
        attempts.addAll(fallbackChain.fallbacks(spec.sql(), ctx));

        QueryResult lastResult = null;
        for (int i = 0; i < attempts.size(); i++) {
            String sql = attempts.get(i);
            String strategy = i == 0 ? "primary" : "fallback_" + i;
            String attemptKey = spec.key() + (i == 0 ? "" : "_fb" + i);
            QuerySpec attempt = new QuerySpec(attemptKey, sql, spec.params());
            long start = System.currentTimeMillis();

            try {
                ComputationResultSet rs = warehouseExecutor.execute(
                        singlePlan(attempt, tenantId), tenantId);
                QueryResult qr = rs.results().isEmpty() ? null : rs.results().getFirst();
                long elapsed = qr != null ? qr.elapsedMs() : System.currentTimeMillis() - start;
                int rowCount = qr != null && qr.rows() != null ? qr.rows().size() : 0;
                boolean success = rowCount > 0;

                diagnosticsOut.add(ExecutionDiagnostics.fromExecution(
                        spec.key(), strategy, i, sql, elapsed,
                        qr != null ? qr.rows() : List.of(), null));

                debugger.log(new QueryExecutionDebugger.ExecutionRecord(
                        candidateId, attemptKey, sql, rowCount, success, elapsed,
                        metric, dimension, success ? null : "zero rows"),
                        qr != null ? qr.rows() : List.of());

                if (success) {
                    return new QueryResult(spec.key(), qr.rows(), elapsed);
                }
                lastResult = qr != null ? qr : new QueryResult(spec.key(), List.of(), elapsed);
            } catch (Exception ex) {
                long elapsed = System.currentTimeMillis() - start;
                diagnosticsOut.add(ExecutionDiagnostics.fromExecution(
                        spec.key(), strategy, i, sql, elapsed, List.of(), ex.getMessage()));
                debugger.log(new QueryExecutionDebugger.ExecutionRecord(
                        candidateId, attemptKey, sql, 0, false, elapsed,
                        metric, dimension, ex.getMessage()));
            }
        }
        return lastResult != null ? lastResult : new QueryResult(spec.key(), List.of(), 0);
    }

    public static boolean isTemplateSpec(QuerySpec spec) {
        return spec.key() != null && spec.key().startsWith("tpl__");
    }

    private MetricPackExecutionPlan singlePlan(QuerySpec spec, String tenantId) {
        return new MetricPackExecutionPlan("analytical", "1", tenantId, List.of(spec));
    }
}
