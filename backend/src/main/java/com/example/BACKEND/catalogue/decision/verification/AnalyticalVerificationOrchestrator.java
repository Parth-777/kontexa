package com.example.BACKEND.catalogue.decision.verification;

import com.example.BACKEND.catalogue.decision.candidate.AnalyticalCandidate;
import com.example.BACKEND.catalogue.decision.candidate.CandidateExecutionOrchestrator.SelectionResult;
import com.example.BACKEND.catalogue.decision.clarification.ResolvedAnalyticalQuestion;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedGroupEntry;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.execution.repair.ExecutionDiagnosticSession;
import com.example.BACKEND.catalogue.decision.execution.repair.ExecutionDiagnostics;
import com.example.BACKEND.catalogue.decision.execution.repair.RepairOutcome;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.QueryExecutionDebugger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Coordinates verification, confidence decomposition, and debug panel assembly.
 * Runs before synthesis — validated SQL/grouped results gate narrative.
 */
@Component
public class AnalyticalVerificationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AnalyticalVerificationOrchestrator.class);

    private final AnalyticalVerificationEngine verificationEngine;
    private final StatisticalNarrativeGuard    narrativeGuard;
    private final ExecutionDiagnosticSession   diagnosticSession;

    public AnalyticalVerificationOrchestrator(
            AnalyticalVerificationEngine verificationEngine,
            StatisticalNarrativeGuard narrativeGuard,
            ExecutionDiagnosticSession diagnosticSession
    ) {
        this.verificationEngine = verificationEngine;
        this.narrativeGuard = narrativeGuard;
        this.diagnosticSession = diagnosticSession;
    }

    public record VerificationContext(
            AnalyticalVerificationEngine.VerificationReport report,
            ConfidenceDecomposition confidence,
            QueryDebugPanel debugPanel,
            StatisticalNarrativeGuard.GuardResult narrativeGuard,
            boolean synthesisAllowed
    ) {}

    public VerificationContext verifyBeforeSynthesis(
            ExecutionFindings executionFindings,
            ComputationResultSet results,
            List<QuerySpec> templateSpecs,
            List<AnalyticalCandidate> candidates,
            SelectionResult candidateSelection,
            ResolvedAnalyticalQuestion resolvedQuestion,
            String question,
            java.util.UUID runId
    ) {
        GoldenAnalyticalBenchmark benchmark = GoldenAnalyticalTestSuite.findByQuestion(question);
        MaterializedQueryResult materialized = executionFindings != null
                ? executionFindings.materializedResult() : null;

        List<QueryResult> templateResults = filterTemplateResults(results, templateSpecs);
        var report = verificationEngine.verify(materialized, templateResults, benchmark);

        int sqlTotal = templateSpecs != null ? templateSpecs.size() : 0;
        int sqlOk = (int) templateResults.stream()
                .filter(q -> q.rows() != null && !q.rows().isEmpty()).count();

        var narrativeStats = narrativeGuard.statsFrom(report);
        var guardResult = narrativeGuard.guard("", narrativeStats);

        var confidence = ConfidenceDecomposition.from(report, sqlOk, sqlTotal, guardResult.strongLanguageAllowed());
        var debugPanel = buildDebugPanel(
                templateSpecs, templateResults, candidates, candidateSelection,
                materialized, report, confidence, runId);

        boolean hasQueryRows = templateResults.stream()
                .anyMatch(r -> r.rows() != null && !r.rows().isEmpty());
        boolean hasMaterialized = materialized != null && materialized.hasContent();
        boolean synthesisAllowed = hasQueryRows || hasMaterialized || report.passed();
        if (!report.passed()) {
            log.warn("[verification] pre-synthesis check failed: {}", report.violations());
        } else {
            log.info("[verification] passed groups={} reconcile={}% cv={} confidence={}",
                    report.groupCount(),
                    String.format("%.1f", report.reconcileDeltaPct()),
                    String.format("%.3f", report.coefficientOfVariation()),
                    String.format("%.2f", confidence.composite()));
        }

        return new VerificationContext(report, confidence, debugPanel, guardResult, synthesisAllowed);
    }

    public String guardNarrative(String text, VerificationContext ctx) {
        if (text == null || ctx == null) return text;
        var stats = narrativeGuard.statsFrom(ctx.report());
        return narrativeGuard.guard(text, stats).sanitizedText();
    }

    private List<QueryResult> filterTemplateResults(
            ComputationResultSet results, List<QuerySpec> templateSpecs
    ) {
        if (results == null || templateSpecs == null) return List.of();
        var keys = templateSpecs.stream().map(QuerySpec::key).collect(java.util.stream.Collectors.toSet());
        return results.results().stream()
                .filter(r -> keys.contains(r.key()) || r.key().startsWith("tpl__"))
                .toList();
    }

    private QueryDebugPanel buildDebugPanel(
            List<QuerySpec> specs,
            List<QueryResult> results,
            List<AnalyticalCandidate> candidates,
            SelectionResult selection,
            MaterializedQueryResult materialized,
            AnalyticalVerificationEngine.VerificationReport report,
            ConfidenceDecomposition confidence,
            java.util.UUID runId
    ) {
        List<RepairOutcome> repairOutcomes = runId != null
                ? diagnosticSession.outcomes(runId) : List.of();
        List<QueryDebugPanel.RepairAttemptEntry> repairEntries = new ArrayList<>();
        for (RepairOutcome outcome : repairOutcomes) {
            for (ExecutionDiagnostics d : outcome.attempts()) {
                repairEntries.add(new QueryDebugPanel.RepairAttemptEntry(
                        d.queryKey(), d.strategy(), d.attemptIndex(), d.rowCount(),
                        d.elapsedMs(), d.failureReason(), d.success(), d.sql()));
            }
        }

        List<QueryDebugPanel.SqlEntry> sqlEntries = new ArrayList<>();
        if (specs != null) {
            for (QuerySpec spec : specs) {
                QueryResult match = results.stream()
                        .filter(r -> r.key().equals(spec.key()) || r.key().startsWith(spec.key()))
                        .findFirst().orElse(null);
                String metric = spec.params() != null ? String.valueOf(spec.params().getOrDefault("metric", "")) : "";
                String dim = spec.params() != null ? String.valueOf(spec.params().getOrDefault("dimension", "")) : "";
                RepairOutcome repair = repairOutcomes.stream()
                        .filter(o -> o.result() != null && spec.key().equals(o.result().key()))
                        .findFirst().orElse(null);
                ExecutionDiagnostics winning = repair != null && !repair.attempts().isEmpty()
                        ? repair.attempts().stream().filter(ExecutionDiagnostics::success).findFirst()
                        .orElse(repair.attempts().getLast()) : null;
                String executedSql = winning != null && winning.sql() != null
                        ? winning.sql() : spec.sql();
                List<java.util.Map<String, Object>> sample = match != null && match.rows() != null
                        ? QueryExecutionDebugger.firstRows(match.rows(), 20) : List.of();
                sqlEntries.add(new QueryDebugPanel.SqlEntry(
                        spec.key(),
                        executedSql,
                        match != null && match.rows() != null ? match.rows().size() : 0,
                        match != null ? match.elapsedMs() : 0,
                        metric, dim,
                        match != null && match.rows() != null && !match.rows().isEmpty(),
                        repair != null ? repair.winningStrategy() : "primary",
                        winning != null ? winning.groupByColumns() : List.of(),
                        winning != null ? winning.whereClause() : "",
                        winning != null && winning.failureReason() != null ? winning.failureReason() : "",
                        sample));
            }
        }

        List<QueryDebugPanel.CandidatePlanEntry> planEntries = new ArrayList<>();
        if (candidates != null) {
            for (AnalyticalCandidate c : candidates) {
                double score = selection != null && selection.hasWinner()
                        && selection.winner().candidate().candidateId().equals(c.candidateId())
                        ? selection.winner().totalScore() : 0;
                planEntries.add(new QueryDebugPanel.CandidatePlanEntry(
                        c.candidateId(), c.label(),
                        c.plan().primaryMetric(), c.dimensionColumn(), score));
            }
        }

        String selected = selection != null && selection.hasWinner()
                ? selection.winner().candidate().label() : "";

        List<QueryDebugPanel.GroupedRowEntry> grouped = new ArrayList<>();
        if (materialized != null && materialized.primaryGrouping() != null) {
            for (MaterializedGroupEntry e : materialized.primaryGrouping().rankedEntries()) {
                grouped.add(new QueryDebugPanel.GroupedRowEntry(
                        e.entityKey(), e.totalValue(), e.sharePct(), e.rank()));
            }
        }

        return new QueryDebugPanel(
                sqlEntries, repairEntries, planEntries, selected, grouped,
                report != null ? report.overallTotal() : 0,
                report != null ? report.groupedTotal() : 0,
                report != null ? report.reconcileDeltaPct() : 0,
                confidence,
                report != null ? report.violations() : List.of(),
                report != null && report.passed()
        );
    }
}
