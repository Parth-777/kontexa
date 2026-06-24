package com.example.BACKEND.catalogue.decision.execution.repair;

import com.example.BACKEND.catalogue.decision.compute.WarehouseExecutor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricPackExecutionPlan;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.QueryExecutionDebugger;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.TemplateContext;
import com.example.BACKEND.catalogue.decision.execution.trace.ExecutionTraceStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Iterative warehouse execution: generate → execute → inspect → repair → retry.
 * Disabled as primary path during stabilization — retained for observability tests.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "kontexa.analytics.query-repair-enabled", havingValue = "true", matchIfMissing = false)
public class QueryRepairLoop {

    private static final Logger log = LoggerFactory.getLogger(QueryRepairLoop.class);
    private static final int MAX_ATTEMPTS = 5;

    private final WarehouseExecutor          warehouseExecutor;
    private final ZeroRowRecoveryEngine      recoveryEngine;
    private final IntermediateResultInspector inspector;
    private final ResultQualityValidator     qualityValidator;
    private final QueryExecutionDebugger     debugger;

    public QueryRepairLoop(
            WarehouseExecutor warehouseExecutor,
            ZeroRowRecoveryEngine recoveryEngine,
            IntermediateResultInspector inspector,
            ResultQualityValidator qualityValidator,
            QueryExecutionDebugger debugger
    ) {
        this.warehouseExecutor = warehouseExecutor;
        this.recoveryEngine = recoveryEngine;
        this.inspector = inspector;
        this.qualityValidator = qualityValidator;
        this.debugger = debugger;
    }

    public RepairOutcome repair(QuerySpec spec, TemplateContext ctx, String tenantId) {
        String metric = ctx.revenueMetric();
        String dimension = ctx.dimensionColumn() != null ? ctx.dimensionColumn() : "";

        List<ZeroRowRecoveryEngine.RecoveryCandidate> candidates =
                recoveryEngine.buildRecoveryChain(spec.sql(), ctx, null);

        List<ExecutionDiagnostics> attempts = new ArrayList<>();
        List<ExecutionTraceStep> traceSteps = new ArrayList<>();
        QueryResult bestResult = null;
        String winningStrategy = "none";
        int bestRowCount = 0;
        ExecutionDiagnostics lastDiag = null;

        int attemptIdx = 0;
        for (ZeroRowRecoveryEngine.RecoveryCandidate candidate : candidates) {
            if (attemptIdx >= MAX_ATTEMPTS) break;

            String attemptKey = spec.key() + (attemptIdx == 0 ? "" : "_repair" + attemptIdx);
            QuerySpec attemptSpec = new QuerySpec(attemptKey, candidate.sql(), spec.params());
            long start = System.currentTimeMillis();

            traceSteps.add(ExecutionTraceStep.pending(
                    "sql_attempt_" + attemptIdx,
                    attemptIdx == 0 ? "Executing generated query" : "Retrying: " + candidate.rationale()));

            try {
                ComputationResultSet rs = warehouseExecutor.execute(
                        singlePlan(attemptSpec, tenantId), tenantId);
                QueryResult qr = rs.results().isEmpty() ? null : rs.results().getFirst();
                long elapsed = qr != null ? qr.elapsedMs() : System.currentTimeMillis() - start;
                List<Map<String, Object>> rows = qr != null ? qr.rows() : List.of();

                ExecutionDiagnostics diag = ExecutionDiagnostics.fromExecution(
                        spec.key(), candidate.strategy(), attemptIdx, candidate.sql(),
                        elapsed, rows, null);

                var inspection = inspector.inspect(rows);
                if (!inspection.acceptable()) {
                    diag = diag.withInspection(inspection.issue());
                }

                var quality = qualityValidator.validateRows(rows);
                boolean acceptable = inspection.acceptable() && quality.acceptable()
                        && !"bounds_probe".equals(candidate.strategy());

                attempts.add(diag);
                debugger.log(new QueryExecutionDebugger.ExecutionRecord(
                        ctx.candidateId(), attemptKey, candidate.sql(),
                        diag.rowCount(), acceptable, elapsed, metric, dimension,
                        acceptable ? null : diag.failureReason()));

                traceSteps.add(buildTraceStep(attemptIdx, candidate, diag, inspection, acceptable));

                if (acceptable) {
                    log.info("[query-repair] success strategy={} rows={} key={}",
                            candidate.strategy(), diag.rowCount(), spec.key());
                    return new RepairOutcome(
                            new QueryResult(spec.key(), rows, elapsed),
                            candidate.strategy(), attemptIdx + 1, attempts, traceSteps,
                            attemptIdx > 0);
                }

                if (diag.rowCount() > bestRowCount) {
                    bestRowCount = diag.rowCount();
                    bestResult = new QueryResult(spec.key(), rows, elapsed);
                    winningStrategy = candidate.strategy();
                }
                lastDiag = diag;

            } catch (Exception ex) {
                long elapsed = System.currentTimeMillis() - start;
                ExecutionDiagnostics diag = ExecutionDiagnostics.fromExecution(
                        spec.key(), candidate.strategy(), attemptIdx, candidate.sql(),
                        elapsed, List.of(), ex.getMessage());
                attempts.add(diag);
                debugger.log(new QueryExecutionDebugger.ExecutionRecord(
                        ctx.candidateId(), attemptKey, candidate.sql(),
                        0, false, elapsed, metric, dimension, ex.getMessage()));
                traceSteps.add(ExecutionTraceStep.pending("sql_attempt_" + attemptIdx,
                                "Query failed: " + ex.getMessage())
                        .failed(elapsed, ex.getMessage()));
                lastDiag = diag;
            }

            attemptIdx++;
        }

        log.warn("[query-repair] exhausted {} attempts for key={} bestRows={}",
                attempts.size(), spec.key(), bestRowCount);

        QueryResult finalResult = bestResult != null ? bestResult
                : new QueryResult(spec.key(), List.of(), 0);
        return new RepairOutcome(
                finalResult, winningStrategy, attempts.size(), attempts, traceSteps,
                bestRowCount > 0 && attempts.size() > 1);
    }

    private ExecutionTraceStep buildTraceStep(
            int idx,
            ZeroRowRecoveryEngine.RecoveryCandidate candidate,
            ExecutionDiagnostics diag,
            IntermediateResultInspector.InspectionResult inspection,
            boolean acceptable
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("strategy", candidate.strategy());
        details.put("row_count", diag.rowCount());
        details.put("message", candidate.rationale());
        if (!acceptable && inspection.issue() != null) {
            details.put("issue", inspection.issue());
        }
        String key = "sql_attempt_" + idx;
        String title = acceptable
                ? "Query returned " + diag.rowCount() + " rows (" + candidate.strategy() + ")"
                : diag.rowCount() == 0
                        ? "Query returned 0 rows — " + candidate.strategy()
                        : "Unstable result (" + inspection.issue() + ") — " + candidate.strategy();
        return ExecutionTraceStep.pending(key, title).completed(diag.elapsedMs(), details);
    }

    private MetricPackExecutionPlan singlePlan(QuerySpec spec, String tenantId) {
        return new MetricPackExecutionPlan("analytical", "1", tenantId, List.of(spec));
    }
}
