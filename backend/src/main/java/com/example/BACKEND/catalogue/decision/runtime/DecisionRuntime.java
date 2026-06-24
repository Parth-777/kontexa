package com.example.BACKEND.catalogue.decision.runtime;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.compute.WarehouseExecutor;
import com.example.BACKEND.catalogue.decision.evidence.EvidenceAssembler;
import com.example.BACKEND.catalogue.decision.metricpack.MetricPackPlanner;
import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthEngine;
import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.execution.framework.IntentDrivenComputationFramework;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalSqlExecutionService;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.ExecutablePlanValidator;
import com.example.BACKEND.catalogue.decision.findings.StructuredFindingsBundle;
import com.example.BACKEND.catalogue.decision.findings.StructuredFindingsEngine;
import com.example.BACKEND.catalogue.decision.calibration.CalibrationResult;
import com.example.BACKEND.catalogue.decision.calibration.ResponseCalibrationEngine;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalPlanningEngine;
import com.example.BACKEND.catalogue.decision.planning.EvidenceCoverageChecker;
import com.example.BACKEND.catalogue.decision.planning.EvidenceCoverageReport;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigation;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.playbooks.Playbook;
import com.example.BACKEND.catalogue.decision.playbooks.PlaybookRouter;
import com.example.BACKEND.catalogue.decision.ranking.MaterialityRankingEngine;
import com.example.BACKEND.catalogue.decision.reasoning.ReasoningConstitutionEngine;
import com.example.BACKEND.catalogue.decision.registry.RegistryResolver;
import com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse;
import com.example.BACKEND.catalogue.decision.grounding.SemanticGroundingService;
import com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponseAssembler;
import com.example.BACKEND.catalogue.decision.candidate.AnalyticalCandidate;
import com.example.BACKEND.catalogue.decision.candidate.CandidateAnalysisGenerator;
import com.example.BACKEND.catalogue.decision.candidate.CandidateExecutionOrchestrator;
import com.example.BACKEND.catalogue.decision.clarification.AnalyticalQuestionResolver;
import com.example.BACKEND.catalogue.decision.clarification.SemanticResolution;
import com.example.BACKEND.catalogue.decision.clarification.InsufficientEvidenceGuard;
import com.example.BACKEND.catalogue.decision.clarification.RecoveryResponseBuilder;
import com.example.BACKEND.catalogue.decision.clarification.RecoveryResponseBuilder.RecoveryKind;
import com.example.BACKEND.catalogue.decision.clarification.ResolvedAnalyticalQuestion;
import com.example.BACKEND.catalogue.decision.governance.MetricGovernanceOrchestrator;
import com.example.BACKEND.catalogue.decision.governance.MetricGovernanceOrchestrator.GovernedFindings;
import com.example.BACKEND.catalogue.decision.governance.MetricGovernanceOrchestrator.GovernedPresentation;
import com.example.BACKEND.catalogue.decision.reasoning.AnalyticalReasoningOrchestrator;
import com.example.BACKEND.catalogue.decision.reasoning.AnalyticalReasoningOrchestrator.ReasoningResult;
import com.example.BACKEND.catalogue.decision.synthesis.ExecutiveSynthesisService;
import com.example.BACKEND.catalogue.decision.synthesis.answer.AnswerSynthesisInputBuilder;
import com.example.BACKEND.catalogue.decision.synthesis.answer.AnswerSynthesisOutput;
import com.example.BACKEND.catalogue.decision.synthesis.answer.AnswerSynthesizer;
import com.example.BACKEND.catalogue.decision.execution.repair.ExecutionDiagnosticSession;
import com.example.BACKEND.catalogue.decision.execution.repair.ExecutionDiagnostics;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.QueryExecutionDebugger;
import com.example.BACKEND.catalogue.decision.execution.trace.ExecutionTrace;
import com.example.BACKEND.catalogue.decision.execution.trace.ExecutionTraceEngine;
import com.example.BACKEND.catalogue.decision.reasoning.QuestionDrivenReasoningPlan;
import com.example.BACKEND.catalogue.decision.verification.AnalyticalVerificationOrchestrator;
import com.example.BACKEND.catalogue.decision.verification.AnalyticalVerificationOrchestrator.VerificationContext;
import com.example.BACKEND.catalogue.decision.verification.QuestionResultValidator;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationBuilder;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationStatisticsBuilder;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalRuntimeFinisher;
import com.example.BACKEND.catalogue.semantic.phase2.GptSemanticPlanningOrchestrator;
import com.example.BACKEND.catalogue.decision.planning.SemanticShadowComparison;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Top-level orchestrator for the decision runtime.
 *
 * Execution flow (fully synchronous for Phase 1):
 *   question → IntentResolution → RegistryResolution → MetricPackExecutionPlan
 *            → WarehouseCompute → EvidenceObjects → RankedEvidence → InsightOutput
 *
 * All inter-stage timeouts and failure handling live here.
 */
@Service
public class DecisionRuntime {

    private static final Logger log = LoggerFactory.getLogger(DecisionRuntime.class);

    private final IntentResolver            intentResolver;
    private final RegistryResolver          registryResolver;
    private final MetricPackPlanner         metricPackPlanner;
    private final WarehouseExecutor         warehouseExecutor;
    private final EvidenceAssembler         evidenceAssembler;
    private final MaterialityRankingEngine      rankingEngine;
    private final ExecutiveSynthesisService     synthesisService;
    private final ExecutionLifecycleManager     lifecycleManager;
    private final PlaybookRouter                playbookRouter;
    private final ReasoningConstitutionEngine   constitutionEngine;
    private final ResponseCalibrationEngine     calibrationEngine;
    private final AnalyticalPlanningEngine      planningEngine;
    private final EvidenceCoverageChecker       coverageChecker;
    private final AnalyticalDepthEngine              depthEngine;
    private final IntentDrivenComputationFramework   executionEngine;
    private final DeterministicAnalyticalQueryPlanner deterministicPlanner;
    private final AnalyticalSqlExecutionService       sqlExecutionService;
    private final ExecutablePlanValidator             planValidator;
    private final StructuredFindingsEngine           findingsEngine;
    private final AnalyticalResponseAssembler        responseAssembler;
    private final SemanticGroundingService           groundingService;
    private final AnalyticalReasoningOrchestrator    reasoningOrchestrator;
    private final MetricGovernanceOrchestrator       metricGovernance;
    private final AnalyticalQuestionResolver         questionResolver;
    private final RecoveryResponseBuilder            recoveryBuilder;
    private final InsufficientEvidenceGuard          evidenceGuard;
    private final CandidateAnalysisGenerator         candidateGenerator;
    private final CandidateExecutionOrchestrator     candidateOrchestrator;
    private final AnalyticalVerificationOrchestrator verificationOrchestrator;
    private final ExecutionTraceEngine                 traceEngine;
    private final ExecutionDiagnosticSession         diagnosticSession;
    private final QuestionResultValidator              questionResultValidator;
    private final GptSemanticPlanningOrchestrator      gptSemanticOrchestrator;
    private final AnswerSynthesizer                    answerSynthesizer;
    private final AnswerSynthesisInputBuilder          answerSynthesisInputBuilder;
    private final ExecutivePresentationBuilder         executivePresentationBuilder;
    private final ExecutivePresentationStatisticsBuilder executivePresentationStatisticsBuilder;

    /** In-memory run registry (Phase 1). Replaced by DB persistence in Phase 2. */
    private final ConcurrentHashMap<UUID, ExecutionRun> runRegistry = new ConcurrentHashMap<>();

    public DecisionRuntime(
            IntentResolver              intentResolver,
            RegistryResolver            registryResolver,
            MetricPackPlanner           metricPackPlanner,
            WarehouseExecutor           warehouseExecutor,
            EvidenceAssembler           evidenceAssembler,
            MaterialityRankingEngine    rankingEngine,
            ExecutiveSynthesisService   synthesisService,
            ExecutionLifecycleManager   lifecycleManager,
            PlaybookRouter              playbookRouter,
            ReasoningConstitutionEngine constitutionEngine,
            ResponseCalibrationEngine   calibrationEngine,
            AnalyticalPlanningEngine    planningEngine,
            EvidenceCoverageChecker              coverageChecker,
            AnalyticalDepthEngine                depthEngine,
            IntentDrivenComputationFramework     executionEngine,
            DeterministicAnalyticalQueryPlanner  deterministicPlanner,
            AnalyticalSqlExecutionService        sqlExecutionService,
            ExecutablePlanValidator              planValidator,
            StructuredFindingsEngine             findingsEngine,
            AnalyticalResponseAssembler          responseAssembler,
            SemanticGroundingService             groundingService,
            AnalyticalReasoningOrchestrator      reasoningOrchestrator,
            MetricGovernanceOrchestrator         metricGovernance,
            AnalyticalQuestionResolver           questionResolver,
            RecoveryResponseBuilder              recoveryBuilder,
            InsufficientEvidenceGuard            evidenceGuard,
            CandidateAnalysisGenerator           candidateGenerator,
            CandidateExecutionOrchestrator       candidateOrchestrator,
            AnalyticalVerificationOrchestrator   verificationOrchestrator,
            ExecutionTraceEngine                 traceEngine,
            ExecutionDiagnosticSession         diagnosticSession,
            QuestionResultValidator              questionResultValidator,
            GptSemanticPlanningOrchestrator      gptSemanticOrchestrator,
            AnswerSynthesizer                    answerSynthesizer,
            AnswerSynthesisInputBuilder          answerSynthesisInputBuilder,
            ExecutivePresentationBuilder         executivePresentationBuilder,
            ExecutivePresentationStatisticsBuilder executivePresentationStatisticsBuilder
    ) {
        this.intentResolver    = intentResolver;
        this.registryResolver  = registryResolver;
        this.metricPackPlanner = metricPackPlanner;
        this.warehouseExecutor = warehouseExecutor;
        this.evidenceAssembler = evidenceAssembler;
        this.rankingEngine     = rankingEngine;
        this.synthesisService  = synthesisService;
        this.lifecycleManager  = lifecycleManager;
        this.playbookRouter    = playbookRouter;
        this.constitutionEngine = constitutionEngine;
        this.calibrationEngine  = calibrationEngine;
        this.planningEngine     = planningEngine;
        this.coverageChecker    = coverageChecker;
        this.depthEngine        = depthEngine;
        this.executionEngine    = executionEngine;
        this.deterministicPlanner = deterministicPlanner;
        this.sqlExecutionService  = sqlExecutionService;
        this.planValidator        = planValidator;
        this.findingsEngine     = findingsEngine;
        this.responseAssembler   = responseAssembler;
        this.groundingService    = groundingService;
        this.reasoningOrchestrator = reasoningOrchestrator;
        this.metricGovernance       = metricGovernance;
        this.questionResolver       = questionResolver;
        this.recoveryBuilder        = recoveryBuilder;
        this.evidenceGuard          = evidenceGuard;
        this.candidateGenerator    = candidateGenerator;
        this.candidateOrchestrator = candidateOrchestrator;
        this.verificationOrchestrator = verificationOrchestrator;
        this.traceEngine = traceEngine;
        this.diagnosticSession = diagnosticSession;
        this.questionResultValidator = questionResultValidator;
        this.gptSemanticOrchestrator = gptSemanticOrchestrator;
        this.answerSynthesizer = answerSynthesizer;
        this.answerSynthesisInputBuilder = answerSynthesisInputBuilder;
        this.executivePresentationBuilder = executivePresentationBuilder;
        this.executivePresentationStatisticsBuilder = executivePresentationStatisticsBuilder;
    }

    /**
     * Execute a single decision run end-to-end.
     * Returns {@link InsightOutput} on success.
     */
    public DecisionRunResult execute(DecisionExecutionContext ctx) {
        UUID runId = ctx.runId();
        ExecutionRun run = lifecycleManager.start(ctx);
        runRegistry.put(runId, run);

        try {
            long t0;

            // Stage 1 — intent + playbook selection
            run = lifecycleManager.transition(run, ExecutionStage.INTENT_RESOLUTION);
            runRegistry.put(runId, run);
            IntentResolution intent = intentResolver.resolve(ctx);
            Playbook playbook = playbookRouter.route(intent);
            log.info("[decision] run={} intent={} playbook={} confidence={}",
                    runId, intent.objectiveKey(), playbook.playbookKey(), intent.confidence());

            // Stage 2 — registry
            run = lifecycleManager.transition(run, ExecutionStage.REGISTRY_RESOLUTION);
            runRegistry.put(runId, run);
            RegistryResolutionBundle bundle = registryResolver.resolve(intent);
            log.info("[decision] run={} entities={} metrics={}", runId,
                    bundle.entities().size(), bundle.metrics().size());

            // Stage 2b — semantic extraction, metric resolution, reasoning plan
            SemanticResolution semanticResolution = questionResolver.resolveFull(intent, bundle);
            ResolvedAnalyticalQuestion resolvedQuestion = semanticResolution.resolved();
            QuestionDrivenReasoningPlan reasoningPlan = semanticResolution.reasoningPlan();
            traceEngine.beginFromPlan(runId, reasoningPlan);
            if (reasoningPlan.transformationSteps() != null && !reasoningPlan.transformationSteps().isEmpty()) {
                traceEngine.injectTransformationSteps(runId, reasoningPlan.transformationSteps());
            }
            seedSemanticTrace(runId, resolvedQuestion);
            log.info("[decision] run={} questionResolved metric={} grouping={} tier={} exec={} exploratory={} confidence={} penalty={}",
                    runId,
                    resolvedQuestion.assumption().primaryMetric(),
                    resolvedQuestion.assumption().grouping(),
                    resolvedQuestion.confidenceTier(),
                    resolvedQuestion.executionMode(),
                    resolvedQuestion.exploratoryMode(),
                    String.format("%.2f", resolvedQuestion.assumption().resolutionConfidence()),
                    String.format("%.2f", resolvedQuestion.confidencePenalty()));
            if (resolvedQuestion.explorationNote() != null && !resolvedQuestion.explorationNote().isBlank()) {
                log.info("[decision] run={} explorationNote={}", runId, resolvedQuestion.explorationNote());
            }

            AnalysisPlan legacyAnalysisPlan = semanticResolution.analysisPlan();
            GptSemanticPlanningOrchestrator.GptPlanningOutcome gptOutcome = null;
            boolean canonicalServed = false;
            AnalysisPlan activeAnalysisPlan = legacyAnalysisPlan;
            List<QuerySpec> analyticalSpecs = List.of();

            if (gptSemanticOrchestrator.isGptMode()) {
                gptOutcome = gptSemanticOrchestrator.plan(
                        intent.question(), ctx.tenantId(), bundle);
                if (gptOutcome.canonicalExecutable()) {
                    canonicalServed = true;
                    activeAnalysisPlan = gptOutcome.analysisPlan();
                    analyticalSpecs = gptOutcome.querySpecs();
                    log.info("[canonical] run={} serving canonical SQL intent={} metric={} dimension={}",
                            runId, activeAnalysisPlan.intent(),
                            activeAnalysisPlan.primaryMetric(), activeAnalysisPlan.dimension());
                } else {
                    log.warn("[canonical] run={} plan not executable ({}), stopping before warehouse",
                            runId, gptOutcome.canonicalValidation().issues());
                    ExecutionTrace trace = traceEngine.finish(runId);
                    run = lifecycleManager.complete(run);
                    runRegistry.put(runId, run);
                    return CanonicalRuntimeFinisher.planNotExecutable(
                            runId, intent.question(), gptOutcome, trace);
                }
            }

            // Stage 3 — analytical planning (exploratory — never short-circuit before execution)
            run = lifecycleManager.transition(run, ExecutionStage.ANALYTICAL_PLANNING);
            runRegistry.put(runId, run);
            InvestigationPlan investigationPlan = planningEngine.plan(
                    intent, activeAnalysisPlan, resolvedQuestion, reasoningPlan);
            log.info("[decision] run={} planId={} intentType={} depth={} steps={}",
                    runId, investigationPlan.planId(), investigationPlan.intentType(),
                    investigationPlan.depth(), investigationPlan.steps().size());

            // Stage 3.5 — deterministic template SQL (no freeform LLM planning)
            t0 = System.nanoTime();
            traceStep(runId, "build_aggregation_plan", "compute_segments", "compute_share",
                    Map.of("intent_type", investigationPlan.intentType().name()));
            run = lifecycleManager.transition(run, ExecutionStage.SEMANTIC_ANALYTICAL_EXECUTION);
            runRegistry.put(runId, run);
            List<AnalyticalCandidate> analyticalCandidates = candidateGenerator.generate(
                    intent, bundle, semanticResolution.semantics(), semanticResolution.metricResolution());
            QuestionInvestigation investigation = semanticResolution.investigation();
            if (investigation != null) {
                log.info("[investigation] run={} entity={} metric={} intent={} dimension={} executable={}",
                        runId,
                        investigation.extraction().businessEntityKey(),
                        investigation.extraction().metricKey(),
                        investigation.extraction().intent(),
                        investigation.dimension().resolved()
                                ? investigation.dimension().groupingAlias() : "UNRESOLVED",
                        investigation.executable());
                if (investigation.discovery() != null) {
                    var d = investigation.discovery();
                    log.info("[semantic-discovery] run={} candidate_metrics={} candidate_dimensions={}",
                            runId, d.candidateMetrics(), d.candidateDimensions());
                    log.info("[semantic-discovery] run={} metric_resolution={} dimension_resolution={} intent={}",
                            runId, d.metricResolution(), d.dimensionResolution(), d.intentResolution());
                }
                if (!investigation.executable()) {
                    log.warn("[investigation] run={} blocked: {}", runId, investigation.blockingReason());
                }
            }
            if (semanticResolution.analysisPlan() != null) {
                var ap = semanticResolution.analysisPlan();
                log.info("[analysis-plan] run={} legacy intent={} metric={} dimension={} relationship={} executable={}",
                        runId, ap.intent(), ap.primaryMetric(), ap.dimension(),
                        ap.relationshipVariable(), ap.executable());
                if (!ap.executable()) {
                    log.warn("[analysis-plan] run={} legacy blocked: {}", runId, ap.blockingReason());
                }
            }
            if (canonicalServed && activeAnalysisPlan != null) {
                log.info("[analysis-plan] run={} active=canonical intent={} metric={} dimension={} relationship={}",
                        runId, activeAnalysisPlan.intent(), activeAnalysisPlan.primaryMetric(),
                        activeAnalysisPlan.dimension(), activeAnalysisPlan.relationshipVariable());
            }
            if (!gptSemanticOrchestrator.isGptMode()) {
                analyticalSpecs = deterministicPlanner.plan(legacyAnalysisPlan, bundle);
            }
            completeTraceStep(runId, "build_aggregation_plan", "compute_segments", "compute_share",
                    elapsedMs(t0), Map.of("template_queries", analyticalSpecs.size(),
                            "candidates", analyticalCandidates.size()));
            log.info("[decision] run={} templateSpecs={}", runId, analyticalSpecs.size());
            String firstEmptyStage = detectFirstEmptyStage(
                    intent.question(),
                    semanticResolution.metricResolution(),
                    investigationPlan,
                    analyticalSpecs,
                    null,
                    canonicalServed);
            logPipelineCheckpoint(runId, intent.question(), semanticResolution.metricResolution(),
                    investigationPlan, analyticalSpecs, null, firstEmptyStage, investigation);

            // Stage 4 — metric pack planning (skipped on canonical path)
            run = lifecycleManager.transition(run, ExecutionStage.METRIC_PACK_PLANNING);
            runRegistry.put(runId, run);
            List<QuerySpec> metricSpecs = List.of();
            if (!canonicalServed) {
                MetricPackExecutionPlan metricPlan = metricPackPlanner.plan(intent, bundle);
                metricSpecs = metricPlan.querySpecs();
            }
            log.info("[decision] run={} totalQueries={} (template={} + metricPack={})",
                    runId, analyticalSpecs.size() + metricSpecs.size(),
                    analyticalSpecs.size(), metricSpecs.size());

            // Stage 5 — compute: templates with fallback chain, then metric pack
            t0 = System.nanoTime();
            traceStep(runId, "execute_warehouse", Map.of("message", "Executing warehouse query"));
            run = lifecycleManager.transition(run, ExecutionStage.WAREHOUSE_COMPUTE);
            runRegistry.put(runId, run);
            List<QueryResult> templateResults = sqlExecutionService.executeTemplateBatch(
                    analyticalSpecs, intent.question(), ctx.tenantId(), runId);
            traceEngine.appendRepairOutcomes(runId, diagnosticSession.outcomes(runId));
            ComputationResultSet metricResults = metricSpecs.isEmpty()
                    ? new ComputationResultSet(runId, List.of(), Map.of())
                    : warehouseExecutor.execute(
                            new MetricPackExecutionPlan("metric_pack", "1", ctx.tenantId(), metricSpecs),
                            ctx.tenantId());
            List<QueryResult> allResults = new java.util.ArrayList<>(templateResults);
            allResults.addAll(metricResults.results());
            ComputationResultSet results = new ComputationResultSet(
                    runId, allResults, metricResults.executionMeta());
            int totalRows = results.results().stream()
                    .mapToInt(r -> r.rows() != null ? r.rows().size() : 0).sum();
            completeTraceStep(runId, "execute_warehouse", elapsedMs(t0),
                    Map.of("result_sets", results.results().size(),
                            "row_count", totalRows,
                            "message", "Warehouse query completed"));
            log.info("[decision] run={} resultSets={} (template={})",
                    runId, results.results().size(), templateResults.size());

            if (canonicalServed) {
                persistWarehouseFacts(
                        runId,
                        intent.question(),
                        semanticResolution.metricResolution(),
                        investigationPlan,
                        analyticalSpecs,
                        results,
                        investigation,
                        activeAnalysisPlan,
                        gptOutcome);
                ExecutionTrace trace = traceEngine.finish(runId);
                run = lifecycleManager.complete(run);
                runRegistry.put(runId, run);
                if (CanonicalRuntimeFinisher.countCanonicalRows(analyticalSpecs, templateResults) == 0) {
                    log.info("[canonical] run={} zero canonical rows — no legacy fallback", runId);
                    return CanonicalRuntimeFinisher.noMatchingData(runId, trace);
                }
                log.info("[canonical] run={} finishing via answer synthesis only", runId);
                return CanonicalRuntimeFinisher.withWarehouseRows(
                        runId,
                        intent.question(),
                        analyticalSpecs,
                        templateResults,
                        gptOutcome,
                        answerSynthesisInputBuilder,
                        answerSynthesizer,
                        executivePresentationBuilder,
                        executivePresentationStatisticsBuilder,
                        trace);
            }

            SemanticShadowComparison semanticShadow = null;
            if (gptSemanticOrchestrator.isShadowMode()) {
                semanticShadow = gptSemanticOrchestrator.shadowCompare(
                        runId,
                        intent.question(),
                        ctx.tenantId(),
                        bundle,
                        legacyAnalysisPlan,
                        analyticalSpecs,
                        templateResults);
            }

            // Stage 5a — candidate-based execution: run all hypotheses, score, select winner
            CandidateExecutionOrchestrator.SelectionResult candidateSelection =
                    candidateOrchestrator.executeAndSelect(
                            results, analyticalCandidates, semanticResolution.metricResolution());
            if (candidateSelection.hasWinner()) {
                double ambiguityPenalty = resolvedQuestion.assumption().ambiguous() ? 0.05 : 0;
                resolvedQuestion = resolvedQuestion.withWinningCandidate(
                        candidateSelection.winner().candidate().plan(),
                        candidateSelection.selectionNote(),
                        ambiguityPenalty);
                log.info("[decision] run={} candidateWinner={} score={}",
                        runId, candidateSelection.winner().candidate().label(),
                        String.format("%.3f", candidateSelection.winner().totalScore()));
            } else {
                log.info("[decision] run={} candidateSelection=no winner from {} candidates",
                        runId, analyticalCandidates.size());
            }

            // Stage 5 — analytical depth computation (structural analysis before evidence assembly)
            run = lifecycleManager.transition(run, ExecutionStage.ANALYTICAL_DEPTH_COMPUTATION);
            runRegistry.put(runId, run);
            AnalyticalDepthResult depthResult = depthEngine.analyse(results, investigationPlan);
            log.info("[decision] run={} depth: buckets={} relationships={} inflections={} composites={}",
                    runId, depthResult.segmentBuckets().size(), depthResult.relationships().size(),
                    depthResult.inflectionPoints().size(), depthResult.compositeScores().size());

            // Stage 6 — dynamic analytical execution (entity construction + ranking + findings)
            run = lifecycleManager.transition(run, ExecutionStage.DYNAMIC_ANALYTICAL_EXECUTION);
            runRegistry.put(runId, run);
            ExecutionFindings executionFindings = executionEngine.execute(results, investigationPlan);
            if (candidateSelection.hasWinner()) {
                executionFindings = executionFindings.withMaterializedResult(
                        candidateSelection.winningMaterialization());
            }
            var planValidation = planValidator.validate(executionFindings.materializedResult());
            if (!planValidation.valid()) {
                log.warn("[decision] run={} plan validation failed: {} — using candidate retry",
                        runId, planValidation.issues());
                if (candidateSelection.hasWinner()) {
                    executionFindings = executionFindings.withMaterializedResult(
                            candidateSelection.winningMaterialization());
                }
            }
            log.info("[decision] run={} execution: entities={} findings={} primaryRanked={} materialized={}",
                    runId, executionFindings.entities().size(),
                    executionFindings.findings().size(), executionFindings.primaryRanking().size(),
                    executionFindings.materializedResult() != null
                            && executionFindings.materializedResult().hasContent());
            firstEmptyStage = detectFirstEmptyStage(
                    intent.question(),
                    semanticResolution.metricResolution(),
                    investigationPlan,
                    analyticalSpecs,
                    executionFindings.materializedResult(),
                    canonicalServed);
            logPipelineCheckpoint(runId, intent.question(), semanticResolution.metricResolution(),
                    investigationPlan, analyticalSpecs, executionFindings.materializedResult(), firstEmptyStage,
                    investigation);

            String tableRef = activeAnalysisPlan != null
                    && activeAnalysisPlan.tableRef() != null
                    ? activeAnalysisPlan.tableRef()
                    : (bundle.entities().isEmpty()
                    ? null : bundle.entities().getFirst().tableRef());
            var questionValidation = questionResultValidator.validate(
                    semanticResolution.semantics(), semanticResolution.metricResolution(),
                    executionFindings, reasoningPlan);
            if (!questionValidation.passed()) {
                log.info("[decision] run={} semantic note (non-blocking): {}",
                        runId, questionValidation.violations());
            }

            // Stage 6b — structured findings (typed analytical discoveries from materialized evidence)
            run = lifecycleManager.transition(run, ExecutionStage.STRUCTURED_FINDINGS);
            runRegistry.put(runId, run);
            StructuredFindingsBundle findingsBundle =
                    findingsEngine.produce(executionFindings, investigationPlan);
            log.info("[decision] run={} findings: contributions={} rankings={} efficiencies={} temporals={} comparatives={} hasFull={}",
                    runId,
                    findingsBundle.contributionFindings().size(),
                    findingsBundle.rankingFindings().size(),
                    findingsBundle.efficiencyFindings().size(),
                    findingsBundle.temporalFindings().size(),
                    findingsBundle.comparativeFindings().size(),
                    findingsBundle.hasStructuredFindings());

            // Stage 7 — evidence
            run = lifecycleManager.transition(run, ExecutionStage.EVIDENCE_ASSEMBLY);
            runRegistry.put(runId, run);
            List<EvidenceObject> evidence = evidenceAssembler.assemble(results, bundle);
            log.info("[decision] run={} evidenceCount={}", runId, evidence.size());

            // Stage 6 — evidence coverage check (validates evidence against the plan)
            run = lifecycleManager.transition(run, ExecutionStage.EVIDENCE_COVERAGE);
            runRegistry.put(runId, run);
            EvidenceCoverageReport coverageReport = coverageChecker.check(investigationPlan, evidence);
            log.info("[decision] run={} coverageScore={} sufficient={} missing={}",
                    runId, String.format("%.2f", coverageReport.coverageScore()),
                    coverageReport.sufficientForSynthesis(), coverageReport.missingDimensions().size());

            // Stage 7 — ranking (playbook applies its weight overrides)
            run = lifecycleManager.transition(run, ExecutionStage.MATERIALITY_RANKING);
            runRegistry.put(runId, run);
            List<RankedEvidence> ranked = rankingEngine.rank(
                    evidence, ctx.tenantId(), playbook.rankingWeightOverrides());
            log.info("[decision] run={} rankedCount={}", runId, ranked.size());

            // Stage 7 — reasoning constitution (epistemic review before LLM)
            run = lifecycleManager.transition(run, ExecutionStage.REASONING_CONSTITUTION);
            runRegistry.put(runId, run);
            ConstitutionReview constitution = constitutionEngine.review(ranked, evidence, intent);
            log.info("[decision] run={} observations={} inferences={} hypotheses={} filteredSpeculation={}",
                    runId, constitution.observations().size(), constitution.analyticalInferences().size(),
                    constitution.hypotheses().size(), constitution.filteredSpeculation().size());

            // Stage 8 — response calibration (determines synthesis depth and mode)
            run = lifecycleManager.transition(run, ExecutionStage.RESPONSE_CALIBRATION);
            runRegistry.put(runId, run);
            CalibrationResult calibration = calibrationEngine.calibrate(intent, ranked, evidence, constitution);
            log.info("[decision] run={} responseMode={}", runId, calibration.mode());

            // Stage 10b — semantic grounding (before synthesis + presentation)
            StructuredFindingsBundle groundedBundle =
                    groundingService.groundFindingsBundle(findingsBundle);
            log.info("[decision] run={} grounded findings: {} → {}",
                    runId, findingsBundle.totalFindingCount(), groundedBundle.totalFindingCount());

            // Stage 10b2 — analytical verification (SQL + grouped results are source of truth)
            t0 = System.nanoTime();
            traceStep(runId, "validate_results", Map.of("message", "Validating aggregation consistency"));
            VerificationContext verificationCtx = verificationOrchestrator.verifyBeforeSynthesis(
                    executionFindings, results, analyticalSpecs, analyticalCandidates,
                    candidateSelection, resolvedQuestion, intent.question(), runId);
            completeTraceStep(runId, "validate_results", elapsedMs(t0),
                    Map.of("passed", verificationCtx.report().passed() && questionValidation.passed(),
                            "violations", verificationCtx.report().violations().size()
                                    + questionValidation.violations().size(),
                            "confidence", verificationCtx.confidence().composite()));
            log.info("[decision] run={} verification passed={} confidence={}",
                    runId, verificationCtx.report().passed(),
                    String.format("%.2f", verificationCtx.confidence().composite()));

            Map<String, Object> warehouseFacts = buildWarehouseFacts(
                    runId, intent.question(), semanticResolution.metricResolution(),
                    investigationPlan, analyticalSpecs, results, executionFindings,
                    planValidation, questionValidation, verificationCtx, firstEmptyStage, investigation,
                    canonicalServed, activeAnalysisPlan, gptOutcome);
            diagnosticSession.recordWarehouseFacts(runId, warehouseFacts);
            logWarehouseFacts(runId, warehouseFacts);

            // Stage 10c — metric governance (reject invalid findings before synthesis)
            GovernedFindings governedFindings = metricGovernance.governBeforeSynthesis(
                    groundedBundle, executionFindings, investigationPlan);
            StructuredFindingsBundle synthesisBundle = governedFindings.bundle();
            log.info("[decision] run={} governance pre-synthesis: accepted={} rejected={}",
                    runId, synthesisBundle.totalFindingCount(), governedFindings.rejectedCount());

            // Stage 10d — analytical reasoning (stats, comparative, narrative, prioritization, chart coupling)
            t0 = System.nanoTime();
            traceStep(runId, "generate_visualization", Map.of("message", "Creating visualization"));
            ReasoningResult reasoningResult = reasoningOrchestrator.enrich(
                    synthesisBundle, depthResult, investigationPlan);
            completeTraceStep(runId, "generate_visualization", elapsedMs(t0),
                    Map.of("chart_type", reasoningResult.primaryChart() != null
                                    ? reasoningResult.primaryChart().getType().name() : "none",
                            "findings", reasoningResult.prioritizedFindings().size()));
            log.info("[decision] run={} reasoning: prioritizedFindings={} primaryChart={}",
                    runId, reasoningResult.prioritizedFindings().size(),
                    reasoningResult.primaryChart() != null
                            ? reasoningResult.primaryChart().getType() : "none");

            // Stage 10e — finding verification + consistency check before presentation
            GovernedPresentation governedPresentation = metricGovernance.governBeforePresentation(
                    reasoningResult, executionFindings, governedFindings.audits(),
                    coverageReport, constitution,
                    governedFindings.rejectedCount(), governedFindings.totalCount(),
                    resolvedQuestion.executionMode());
            log.info("[decision] run={} governance presentation: findings={} trust={} rejects={}",
                    runId, governedPresentation.reasoning().prioritizedFindings().size(),
                    String.format("%.2f", governedPresentation.trustScore().confidence()),
                    governedPresentation.rejectReasons().size());

            // Stage 10f — post-warehouse answer synthesis (optional, feature-flagged)
            AnswerSynthesisOutput answerSynthesisResult = null;
            boolean warehouseExecuted = results.results().stream()
                    .anyMatch(r -> r.rows() != null && !r.rows().isEmpty());
            if (warehouseExecuted) {
                double synthesisConfidence = Math.min(
                        governedPresentation.trustScore().confidence(),
                        verificationCtx.confidence().composite());
                var synthesisInput = answerSynthesisInputBuilder.build(
                        intent.question(), analyticalSpecs, results,
                        semanticResolution.metricResolution(), investigationPlan,
                        synthesisConfidence,
                        executionFindings != null ? executionFindings.materializedResult() : null,
                        runId,
                        canonicalServed && gptOutcome != null
                                ? gptOutcome.canonicalQueryModel() : null);
                answerSynthesisResult = answerSynthesizer.synthesize(synthesisInput).orElse(null);
            }

            // Stage 11 — synthesis (LLM verbalizes validated findings only)
            t0 = System.nanoTime();
            traceStep(runId, "synthesize_insight", Map.of("message", "Summarizing findings"));
            run = lifecycleManager.transition(run, ExecutionStage.EXECUTIVE_SYNTHESIS);
            runRegistry.put(runId, run);
            InsightOutput output;
            boolean hasExecutableData = synthesisBundle.hasStructuredFindings()
                    || (executionFindings != null && executionFindings.materializedResult() != null
                            && executionFindings.materializedResult().hasContent())
                    || results.results().stream().anyMatch(r -> r.rows() != null && !r.rows().isEmpty());
            if (hasExecutableData) {
                if (answerSynthesisResult != null) {
                    output = insightFromAnswerSynthesis(answerSynthesisResult, runId);
                    log.info("[decision] run={} insight from answer-synthesis type={}",
                            runId, answerSynthesisResult.answerType());
                } else {
                    output = synthesisService.synthesise(
                            ranked, evidence, intent, playbook, constitution, calibration,
                            investigationPlan, coverageReport, depthResult, executionFindings,
                            synthesisBundle);
                    log.info("[decision] run={} insightId={}", runId, output.insightId());
                }
            } else {
                output = new InsightOutput(
                        runId.toString(),
                        "Analysis",
                        verificationOrchestrator.guardNarrative(
                                governedPresentation.reasoning().prioritizedFindings().isEmpty()
                                        ? "Insufficient verified analytical evidence."
                                        : governedPresentation.reasoning().prioritizedFindings().getFirst().businessNarrative(),
                                verificationCtx),
                        List.of(), List.of(), List.of(), List.of(), List.of(), "");
                log.warn("[decision] run={} synthesis skipped — verification or findings insufficient", runId);
            }
            completeTraceStep(runId, "synthesize_insight", elapsedMs(t0),
                    Map.of("synthesis_allowed", verificationCtx.synthesisAllowed()));

            var evidenceAssessment = evidenceGuard.assess(
                    synthesisBundle, governedPresentation.reasoning(),
                    executionFindings, depthResult);
            if (!evidenceAssessment.strongFindings()) {
                log.info("[decision] run={} exploratory presentation: {}", runId, evidenceAssessment.reason());
            }

            double assemblyConfidence = Math.min(
                    governedPresentation.trustScore().confidence(),
                    verificationCtx.confidence().composite());
            if (evidenceAssessment.confidencePenalty() > 0) {
                assemblyConfidence = Math.max(0.35,
                        assemblyConfidence - evidenceAssessment.confidencePenalty());
            }

            ExecutionTrace trace = traceEngine.finish(runId);
            AnalyticalResponse analytical = responseAssembler.assemble(
                    synthesisBundle, depthResult, output, constitution, coverageReport,
                    intent, ranked, evidence, governedPresentation.reasoning(),
                    assemblyConfidence,
                    investigationPlan, executionFindings, resolvedQuestion, results,
                    evidenceAssessment, verificationCtx, trace, answerSynthesisResult);
            log.info("[decision] run={} analytical: findings={} metrics={} chart={} mode={} confidence={}",
                    runId, analytical.findings().size(), analytical.metrics().size(),
                    analytical.chartSpec() != null ? analytical.chartSpec().getType() : "none",
                    analytical.responseMode(),
                    String.format("%.2f", analytical.confidence()));

            // Done
            run = lifecycleManager.complete(run);
            runRegistry.put(runId, run);
            return new DecisionRunResult(output, analytical, verificationCtx, semanticShadow, answerSynthesisResult);

        } catch (Exception ex) {
            log.error("[decision] run={} FAILED stage={} error={}", runId, run.stage(), ex.getMessage(), ex);
            run = lifecycleManager.fail(run, ex.getMessage());
            runRegistry.put(runId, run);
            throw new DecisionRuntimeException("Decision run failed at stage " + run.stage(), ex);
        }
    }

    /** Retrieve the current state of a run (for status polling). */
    public ExecutionRun status(UUID runId) {
        ExecutionRun run = runRegistry.get(runId);
        if (run == null) throw new DecisionRuntimeException("Unknown runId: " + runId);
        return run;
    }

    /** Live execution trace for progressive UI updates. */
    public ExecutionTrace executionTrace(UUID runId) {
        return traceEngine.get(runId);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private void seedSemanticTrace(UUID runId, ResolvedAnalyticalQuestion resolved) {
        Map<String, Object> details = Map.of(
                "metric", resolved.assumption().primaryMetric(),
                "grouping", resolved.assumption().grouping() != null ? resolved.assumption().grouping() : "");
        for (String key : List.of("resolve_metrics", "identify_contribution", "resolve_dimension")) {
            if (hasTraceStep(runId, key)) {
                traceEngine.startStep(runId, key, details);
                traceEngine.completeStep(runId, key, 0, details);
            }
        }
    }

    private void traceStep(UUID runId, String key, Map<String, Object> details) {
        if (hasTraceStep(runId, key)) traceEngine.startStep(runId, key, details);
    }

    private void traceStep(UUID runId, String k1, String k2, String k3, Map<String, Object> details) {
        if (hasTraceStep(runId, k1)) { traceEngine.startStep(runId, k1, details); return; }
        if (hasTraceStep(runId, k2)) { traceEngine.startStep(runId, k2, details); return; }
        if (hasTraceStep(runId, k3)) { traceEngine.startStep(runId, k3, details); }
    }

    private void completeTraceStep(UUID runId, String key, long ms, Map<String, Object> details) {
        if (hasTraceStep(runId, key)) traceEngine.completeStep(runId, key, ms, details);
    }

    private void completeTraceStep(UUID runId, String k1, String k2, String k3, long ms, Map<String, Object> details) {
        if (hasTraceStep(runId, k1)) { traceEngine.completeStep(runId, k1, ms, details); return; }
        if (hasTraceStep(runId, k2)) { traceEngine.completeStep(runId, k2, ms, details); return; }
        if (hasTraceStep(runId, k3)) { traceEngine.completeStep(runId, k3, ms, details); }
    }

    private boolean hasTraceStep(UUID runId, String key) {
        return traceEngine.get(runId).steps().stream().anyMatch(s -> s.stepKey().equals(key));
    }

    private void persistWarehouseFacts(
            UUID runId,
            String question,
            MetricResolution resolution,
            InvestigationPlan investigationPlan,
            List<QuerySpec> specs,
            ComputationResultSet results,
            QuestionInvestigation investigation,
            AnalysisPlan activeAnalysisPlan,
            GptSemanticPlanningOrchestrator.GptPlanningOutcome gptOutcome
    ) {
        String firstEmptyStage = detectFirstEmptyStage(
                question, resolution, investigationPlan, specs, null, true);
        Map<String, Object> warehouseFacts = buildWarehouseFacts(
                runId,
                question,
                resolution,
                investigationPlan,
                specs,
                results,
                null,
                null,
                null,
                null,
                firstEmptyStage,
                investigation,
                true,
                activeAnalysisPlan,
                gptOutcome);
        diagnosticSession.recordWarehouseFacts(runId, warehouseFacts);
        logWarehouseFacts(runId, warehouseFacts);
    }

    private Map<String, Object> buildWarehouseFacts(
            UUID runId,
            String question,
            MetricResolution resolution,
            InvestigationPlan investigationPlan,
            List<QuerySpec> specs,
            ComputationResultSet results,
            ExecutionFindings executionFindings,
            ExecutablePlanValidator.ValidationResult planValidation,
            QuestionResultValidator.ValidationResult questionValidation,
            VerificationContext verificationCtx,
            String firstEmptyStage,
            QuestionInvestigation investigation,
            boolean canonicalServed,
            AnalysisPlan activeAnalysisPlan,
            GptSemanticPlanningOrchestrator.GptPlanningOutcome gptOutcome
    ) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("question", question != null ? question : "");
        facts.put("planner_mode", gptSemanticOrchestrator.isGptMode() ? "gpt"
                : (gptSemanticOrchestrator.isShadowMode() ? "shadow" : "legacy"));
        facts.put("served_path", canonicalServed ? "canonical" : "legacy");
        if (canonicalServed && activeAnalysisPlan != null) {
            facts.put("resolved_metric", activeAnalysisPlan.primaryMetric());
            facts.put("resolved_dimension", activeAnalysisPlan.dimension());
            facts.put("resolved_intent", activeAnalysisPlan.intent() != null
                    ? activeAnalysisPlan.intent().name() : null);
            facts.put("resolved_relationship_variable", activeAnalysisPlan.relationshipVariable());
        } else {
            facts.put("resolved_metric", resolution != null ? resolution.primaryMetric() : null);
            facts.put("resolved_dimension", resolution != null ? resolution.dimension() : null);
        }
        if (gptOutcome != null && gptOutcome.canonicalValidation() != null) {
            facts.put("canonical_validation_valid", gptOutcome.canonicalValidation().valid());
            if (!gptOutcome.canonicalValidation().issues().isEmpty()) {
                facts.put("canonical_validation_issues", gptOutcome.canonicalValidation().issues());
            }
        }
        if (gptOutcome != null && gptOutcome.semanticPlan() != null) {
            facts.put("gpt_confidence", gptOutcome.semanticPlan().confidence());
            facts.put("gpt_validation_valid", gptOutcome.validation().valid());
            if (!gptOutcome.validation().issues().isEmpty()) {
                facts.put("gpt_validation_issues", gptOutcome.validation().issues());
            }
        }
        if (investigation != null && investigation.discovery() != null) {
            facts.putAll(investigation.discovery().toMap());
        }
        facts.put("investigation_plan", investigationPlan != null ? investigationPlan.planId() : null);
        facts.put("first_empty_stage", firstEmptyStage != null ? firstEmptyStage : "");

        String generatedSql = "";
        int warehouseRowCount = 0;
        List<Map<String, Object>> sampleRows = List.of();

        if (specs != null && !specs.isEmpty() && specs.getFirst().sql() != null) {
            generatedSql = specs.getFirst().sql();
        }

        if (verificationCtx != null && verificationCtx.debugPanel() != null
                && !verificationCtx.debugPanel().generatedSql().isEmpty()) {
            var entry = verificationCtx.debugPanel().generatedSql().getFirst();
            generatedSql = entry.sql() != null ? entry.sql() : "";
            warehouseRowCount = entry.rowCount();
            sampleRows = entry.sampleRows() != null ? entry.sampleRows() : List.of();
        } else if (specs != null && !specs.isEmpty() && results != null) {
            QuerySpec primarySpec = specs.getFirst();
            generatedSql = primarySpec.sql() != null ? primarySpec.sql() : "";
            var keys = specs.stream().map(QuerySpec::key).collect(java.util.stream.Collectors.toSet());
            QueryResult primaryResult = results.results().stream()
                    .filter(r -> keys.contains(r.key()) || r.key().startsWith("tpl__"))
                    .findFirst().orElse(null);
            if (primaryResult != null && primaryResult.rows() != null) {
                warehouseRowCount = primaryResult.rows().size();
                sampleRows = QueryExecutionDebugger.firstRows(primaryResult.rows(), 20);
            }
        }

        StringBuilder bqError = new StringBuilder();
        if (results != null && results.executionMeta() != null) {
            results.executionMeta().entrySet().stream()
                    .filter(e -> e.getKey().startsWith("error."))
                    .forEach(e -> bqError.append(e.getKey()).append(": ").append(e.getValue()).append("; "));
        }
        for (ExecutionDiagnostics attempt : diagnosticSession.allAttempts(runId)) {
            if (attempt.warehouseError() != null && !attempt.warehouseError().isBlank()) {
                bqError.append(attempt.queryKey()).append(": ").append(attempt.warehouseError()).append("; ");
            }
        }
        if (verificationCtx != null && verificationCtx.debugPanel() != null
                && !verificationCtx.debugPanel().generatedSql().isEmpty()) {
            String failureReason = verificationCtx.debugPanel().generatedSql().getFirst().failureReason();
            if (failureReason != null && !failureReason.isBlank()) {
                bqError.append(failureReason).append("; ");
            }
        }

        boolean hasMaterialized = executionFindings != null
                && executionFindings.materializedResult() != null
                && executionFindings.materializedResult().hasContent();
        List<String> discardReasons = new ArrayList<>();
        if (warehouseRowCount > 0 && !hasMaterialized) {
            discardReasons.add("Warehouse returned rows but materialization produced no analytical output");
        }
        if (planValidation != null && !planValidation.valid()) {
            discardReasons.addAll(planValidation.issues());
        }
        if (verificationCtx != null && !verificationCtx.report().passed()) {
            discardReasons.addAll(verificationCtx.report().violations());
        }
        if (questionValidation != null && !questionValidation.passed()) {
            discardReasons.addAll(questionValidation.violations());
        }
        boolean rowsDiscarded = warehouseRowCount > 0
                && (!hasMaterialized
                || (planValidation != null && !planValidation.valid())
                || (verificationCtx != null && !verificationCtx.report().passed()));

        MaterializedQueryResult materialized = executionFindings != null
                ? executionFindings.materializedResult() : null;
        List<Map<String, Object>> materializedRows = List.of();
        if (materialized != null && materialized.hasContent()) {
            materializedRows = switch (materialized.resultType()) {
                case CORRELATION_RESULT -> {
                    var c = materialized.correlation();
                    if (c == null) yield List.of();
                    yield List.of(Map.of(
                            "correlation_coefficient", c.correlationCoefficient(),
                            "sample_size", c.sampleSize(),
                            "source_variable", c.sourceVariable(),
                            "target_variable", c.targetVariable(),
                            "interpretation", c.interpretation()));
                }
                case SCALAR_RESULT -> {
                    var s = materialized.scalar();
                    if (s == null) yield List.of();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("metric", s.metricLabel());
                    row.put("value", s.value());
                    if (s.sharePct() != null) row.put("share_pct", s.sharePct());
                    if (s.segmentLabel() != null) row.put("segment", s.segmentLabel());
                    if (s.supportingCount() != null) row.put("row_count", s.supportingCount());
                    yield List.of(row);
                }
                case GROUPED_RESULT -> {
                    if (materialized.primaryGrouping() == null
                            || materialized.primaryGrouping().rankedEntries() == null) {
                        yield List.of();
                    }
                    yield materialized.primaryGrouping().rankedEntries().stream()
                            .limit(20)
                            .map(e -> Map.<String, Object>of(
                                    "entity", e.entityKey(),
                                    "value", e.totalValue(),
                                    "share_pct", e.sharePct()))
                            .toList();
                }
            };
        }
        facts.put("materialized_rows", materializedRows);
        facts.put("materialization_failure_reason",
                warehouseRowCount > 0 && !hasMaterialized
                        ? String.join("; ", discardReasons) : "");
        facts.put("materialized_query", materialized != null && materialized.hasContent()
                ? switch (materialized.resultType()) {
                    case CORRELATION_RESULT -> "correlation rows=" + materialized.totalRows();
                    case SCALAR_RESULT -> "scalar rows=" + materialized.totalRows();
                    case GROUPED_RESULT -> "rows=" + materialized.totalRows() + " groups="
                            + (materialized.primaryGrouping() != null
                            ? materialized.primaryGrouping().rankedEntries().size() : 0);
                }
                : "NONE");

        facts.put("generated_sql", generatedSql);
        facts.put("warehouse_row_count", warehouseRowCount);
        facts.put("sample_rows", sampleRows);
        facts.put("bigquery_error", bqError.toString().trim());
        facts.put("rows_discarded_by_validation", rowsDiscarded);
        facts.put("validation_discard_reason", String.join("; ", discardReasons));
        return facts;
    }

    private String detectFirstEmptyStage(
            String question,
            MetricResolution resolution,
            InvestigationPlan investigationPlan,
            List<QuerySpec> specs,
            MaterializedQueryResult materialized,
            boolean canonicalServed
    ) {
        if (question == null || question.isBlank()) return "question";
        if (canonicalServed) {
            if (investigationPlan == null) return "investigation_plan";
            if (specs == null || specs.isEmpty()
                    || specs.getFirst().sql() == null || specs.getFirst().sql().isBlank()) {
                return "generated_sql";
            }
            if (materialized != null && !materialized.hasContent()) return "materialized_query";
            return null;
        }
        if (resolution == null || resolution.primaryMetric() == null || resolution.primaryMetric().isBlank()) {
            return "resolved_metric";
        }
        if (resolution.dimension() == null || resolution.dimension().isBlank()) {
            if (resolution.isRelationshipAnalysis()) {
                // Relationship questions intentionally have no grouping dimension.
            } else {
                return "resolved_dimension";
            }
        }
        if (investigationPlan == null) return "investigation_plan";
        if (specs == null || specs.isEmpty()
                || specs.getFirst().sql() == null || specs.getFirst().sql().isBlank()) {
            return "generated_sql";
        }
        if (materialized != null) {
            if (!materialized.hasContent()) return "materialized_query";
        }
        return null;
    }

    private void logPipelineCheckpoint(
            UUID runId,
            String question,
            MetricResolution resolution,
            InvestigationPlan investigationPlan,
            List<QuerySpec> specs,
            MaterializedQueryResult materialized,
            String firstEmptyStage,
            QuestionInvestigation investigation
    ) {
        String sql = specs != null && !specs.isEmpty() && specs.getFirst().sql() != null
                ? specs.getFirst().sql() : "NONE";
        String materializedLabel = materialized != null && materialized.hasContent()
                ? "rows=" + materialized.totalRows() : "NONE";
        log.info("[pipeline] run={} question={}", runId, question);
        log.info("[pipeline] run={} resolved_metric={}", runId,
                resolution != null ? resolution.primaryMetric() : "null");
        log.info("[pipeline] run={} resolved_dimension={}", runId,
                resolution != null ? resolution.dimension() : "null");
        log.info("[pipeline] run={} resolved_relationship_variable={}", runId,
                resolution != null ? resolution.relationshipVariable() : "null");
        log.info("[pipeline] run={} resolved_intent={}", runId,
                investigation != null && investigation.extraction() != null
                        ? investigation.extraction().intent()
                        : (resolution != null && resolution.isRelationshipAnalysis()
                                ? "RELATIONSHIP" : "unknown"));
        if (investigation != null && investigation.discovery() != null) {
            var d = investigation.discovery();
            log.info("[pipeline] run={} candidate_metrics={}", runId, d.candidateMetrics());
            log.info("[pipeline] run={} candidate_dimensions={}", runId, d.candidateDimensions());
            log.info("[pipeline] run={} metric_resolution={} dimension_resolution={}",
                    runId, d.metricResolution(), d.dimensionResolution());
        }
        log.info("[pipeline] run={} investigation_plan={}", runId,
                investigationPlan != null ? investigationPlan.planId() : "null");
        log.info("[pipeline] run={} generated_sql={}", runId, sql);
        log.info("[pipeline] run={} materialized_query={}", runId, materializedLabel);
        if (firstEmptyStage != null) {
            log.warn("[pipeline-null] run={} first_empty_stage={}", runId, firstEmptyStage);
        }
    }

    private void logWarehouseFacts(UUID runId, Map<String, Object> facts) {
        log.info("[warehouse-exec] run={} sql={}", runId, facts.get("generated_sql"));
        log.info("[warehouse-exec] run={} row_count={}", runId, facts.get("warehouse_row_count"));
        log.info("[warehouse-exec] run={} sample_rows={}", runId, facts.get("sample_rows"));
        log.info("[warehouse-exec] run={} bigquery_error={}", runId, facts.get("bigquery_error"));
        log.info("[warehouse-exec] run={} rows_discarded_by_validation={} reason={}",
                runId, facts.get("rows_discarded_by_validation"), facts.get("validation_discard_reason"));
        log.info("[warehouse-exec] run={} materialized_rows={} materialization_failure={}",
                runId, facts.get("materialized_rows"), facts.get("materialization_failure_reason"));
    }

    private InsightOutput insightFromAnswerSynthesis(AnswerSynthesisOutput synthesis, UUID runId) {
        String narrative = synthesis.executiveSummary() != null ? synthesis.executiveSummary() : "";
        String rationale = synthesis.confidenceExplanation() != null
                ? synthesis.confidenceExplanation() : "";
        return new InsightOutput(
                runId.toString(),
                "Analysis",
                narrative,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                rationale);
    }

    /** Create an execution context from a raw HTTP request body. */
    public static DecisionExecutionContext contextFrom(String tenantId, String question, Map<String, Object> meta) {
        return new DecisionExecutionContext(UUID.randomUUID(), tenantId, question, meta);
    }
}
