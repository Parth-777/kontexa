package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.repair.ExecutionDiagnosticSession;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalSqlExecutionService;
import com.example.BACKEND.catalogue.decision.execution.trace.ExecutionTrace;
import com.example.BACKEND.catalogue.decision.spi.InvestigationRuntimePort;
import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.GptSemanticPlanningOrchestrator.GptPlanningOutcome;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticCatalogueFactory;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Evidence-centric investigation runtime.
 *
 * <p>Flow: InvestigationPlan → EvidenceRequest[] → EvidenceRequestSqlCompiler →
 * executeTemplateBatch → RequestResults[] → EvidencePack → Reasoning → Answer
 *
 * <p>No investigation categories. No playbooks. No kind-based interpretation routing.
 * {@code EvidenceRequest.kind} is used only by the SQL compiler.
 */
@Service
public class InvestigationRuntime implements InvestigationRuntimePort {

    private static final Logger log = LoggerFactory.getLogger(InvestigationRuntime.class);
    private static final ObjectMapper PLAN_JSON = new ObjectMapper();

    private final InvestigationPlanMapper planMapper;
    private final EvidenceRequestSqlCompiler requestSqlCompiler;
    private final EvidencePackAssembler evidencePackAssembler;
    private final NarrativeRenderer narrativeRenderer;
    private final AnalyticalSqlExecutionService sqlExecutionService;
    private final ExecutionDiagnosticSession diagnosticSession;
    private final InvestigationPlanFieldValidator planFieldValidator;
    private final CatalogueApprovalService catalogueApprovalService;

    public InvestigationRuntime(InvestigationPlanMapper planMapper,
                                EvidenceRequestSqlCompiler requestSqlCompiler,
                                EvidencePackAssembler evidencePackAssembler,
                                NarrativeRenderer narrativeRenderer,
                                AnalyticalSqlExecutionService sqlExecutionService,
                                ExecutionDiagnosticSession diagnosticSession,
                                InvestigationPlanFieldValidator planFieldValidator,
                                CatalogueApprovalService catalogueApprovalService) {
        this.planMapper = planMapper;
        this.requestSqlCompiler = requestSqlCompiler;
        this.evidencePackAssembler = evidencePackAssembler;
        this.narrativeRenderer = narrativeRenderer;
        this.sqlExecutionService = sqlExecutionService;
        this.diagnosticSession = diagnosticSession;
        this.planFieldValidator = planFieldValidator;
        this.catalogueApprovalService = catalogueApprovalService;
    }

    @Override
    public DecisionRunResult run(String runId,
                                 String question,
                                 String tenantId,
                                 GptPlanningOutcome planningOutcome,
                                 RegistryResolutionBundle bundle,
                                 ExecutionTrace trace) {
        Phase2InvestigationPathTrace pathTrace = Phase2InvestigationPathTrace.begin(runId, question, tenantId);
        try {
            return runTraced(runId, question, tenantId, planningOutcome, bundle, pathTrace);
        } catch (Throwable ex) {
            pathTrace.setException(ex);
            pathTrace.setRootCauseSummary(classifyRootCause(ex, pathTrace));
            pathTrace.writeToFile(safeFileHint(question, runId));
            pathTrace.logSummary();
            recordTraceFacts(runId, pathTrace);
            throw ex instanceof RuntimeException re ? re : new RuntimeException(ex);
        } finally {
            // Leave CURRENT until DecisionController / DecisionRuntime finish so mapper failures
            // can still attach; cleared explicitly after the HTTP response is shaped.
        }
    }

    private DecisionRunResult runTraced(
            String runId,
            String question,
            String tenantId,
            GptPlanningOutcome planningOutcome,
            RegistryResolutionBundle bundle,
            Phase2InvestigationPathTrace pathTrace
    ) {
        InvestigationPlan plan = planMapper.map(planningOutcome);
        if (plan == null || plan.evidenceRequests() == null || plan.evidenceRequests().isEmpty()) {
            pathTrace.step("plan_missing", "missing evidence requests from planner");
            pathTrace.setRootCauseSummary("InvestigationPlan missing or empty evidence_requests");
            pathTrace.writeToFile(safeFileHint(question, runId));
            pathTrace.logSummary();
            recordTraceFacts(runId, pathTrace);
            return narrativeRenderer.insufficient(runId,
                    "Investigation not applicable: missing evidence requests from planner");
        }

        try {
            pathTrace.setInvestigationPlan(PLAN_JSON.valueToTree(planToMap(plan)));
        } catch (Exception e) {
            pathTrace.setInvestigationPlan(String.valueOf(plan));
            pathTrace.step("plan_serialize_warn", e.getMessage());
        }

        String tableRef = resolveTableRef(planningOutcome, bundle);
        pathTrace.put("table_ref", tableRef);
        pathTrace.step("table_ref", tableRef != null ? tableRef : "(null)");
        if (tableRef == null || tableRef.isBlank()) {
            pathTrace.setRootCauseSummary("No resolved table reference");
            pathTrace.writeToFile(safeFileHint(question, runId));
            pathTrace.logSummary();
            recordTraceFacts(runId, pathTrace);
            return narrativeRenderer.insufficient(runId,
                    "Investigation not applicable: no resolved table reference");
        }

        String resolvedMetric = primaryMetric(plan);
        String resolvedDimension = primaryDimension(plan);
        pathTrace.setResolvedMetric(resolvedMetric);
        pathTrace.setResolvedDimension(resolvedDimension);
        for (InvestigationPlan.EvidenceRequest req : plan.evidenceRequests()) {
            Map<String, Object> er = new LinkedHashMap<>();
            er.put("request_id", req.requestId());
            er.put("kind", req.kind());
            er.put("role", req.role());
            er.put("hypothesis_id", req.hypothesisId());
            er.put("evidence_purpose", req.evidencePurpose());
            er.put("dimension", req.dimension());
            if (req.measure() != null) {
                er.put("measure_column", req.measure().column());
                er.put("measure_aggregation", req.measure().aggregation());
            }
            pathTrace.addEvidenceRequest(er);
        }

        ApprovedCatalogueSnapshot catalogue = loadCatalogue(tenantId, bundle);
        InvestigationPlanFieldValidator.Result fieldValidation =
                planFieldValidator.validate(plan, catalogue);
        if (!fieldValidation.valid()) {
            pathTrace.step("field_validation_failed", fieldValidation.summaryMessage());
            pathTrace.put("field_validation_issues", fieldValidation.toDiagnostics());
            pathTrace.setRootCauseSummary(
                    "Investigation plan references non-leaf nested field(s): "
                            + fieldValidation.summaryMessage());
            pathTrace.writeToFile(safeFileHint(question, runId));
            pathTrace.logSummary();
            recordTraceFacts(runId, pathTrace);
            return narrativeRenderer.insufficient(runId,
                    "Investigation cannot proceed: " + fieldValidation.summaryMessage());
        }

        // Post-binder dimension contract: never reach requireDimension with a null dimension
        // simply because GPT omitted the field (binder already applied when top-level dims exist).
        EvidenceRequestDimensionContract.Evaluation dimensionGate =
                EvidenceRequestDimensionContract.evaluate(plan);
        if (dimensionGate.hasRejectedMissingDimension()) {
            pathTrace.step("dimension_contract",
                    "rejected=" + dimensionGate.rejected().size()
                            + " executable=" + dimensionGate.executableRequests().size());
            pathTrace.put("dimension_contract_issues", dimensionGate.toDiagnostics());
        }
        if (dimensionGate.allRequestsRejectedForMissingDimension(plan)) {
            pathTrace.setRootCauseSummary(EvidenceRequestDimensionContract.MISSING_DIMENSION_REASON);
            pathTrace.writeToFile(safeFileHint(question, runId));
            pathTrace.logSummary();
            recordTraceFacts(runId, pathTrace);
            return narrativeRenderer.insufficient(runId,
                    EvidenceRequestDimensionContract.MISSING_DIMENSION_REASON);
        }

        InvestigationPlan compilePlan =
                EvidenceRequestDimensionContract.planForCompilation(plan, dimensionGate);
        EvidenceRequestSqlCompiler.CompiledProgram compiled =
                requestSqlCompiler.compile(compilePlan, tableRef);
        pathTrace.step("compile", "compiled " + compiled.requests().size() + " request(s)");

        StringBuilder sqlJoined = new StringBuilder();
        Map<String, EvidenceRequestSqlCompiler.CompiledRequest> compiledById = new LinkedHashMap<>();
        for (EvidenceRequestSqlCompiler.CompiledRequest cr : compiled.requests()) {
            compiledById.put(cr.request().requestId(), cr);
            if (cr.ok()) {
                pathTrace.addCompiledSql(
                        cr.request().requestId(), cr.request().kind(), true, cr.spec().sql(), null);
                if (sqlJoined.length() > 0) {
                    sqlJoined.append("\n;\n");
                }
                sqlJoined.append("-- ").append(cr.request().requestId())
                        .append(" [").append(cr.request().kind()).append("]\n")
                        .append(cr.spec().sql());
            } else {
                pathTrace.addCompiledSql(
                        cr.request().requestId(), cr.request().kind(), false, null, cr.error());
            }
        }
        for (EvidenceRequestDimensionContract.Rejected rejected : dimensionGate.rejected()) {
            pathTrace.addCompiledSql(
                    rejected.request().requestId(),
                    rejected.request().kind(),
                    false,
                    null,
                    rejected.reason());
        }
        pathTrace.setGeneratedSql(sqlJoined.toString());

        List<QuerySpec> specs = compiled.executableSpecs();
        pathTrace.step("execute_batch", "executable_specs=" + specs.size());
        List<QueryResult> results = specs.isEmpty()
                ? List.of()
                : sqlExecutionService.executeTemplateBatch(
                        specs, question, tenantId, parseRunId(runId));

        Map<String, QueryResult> byKey = toResultMap(results);
        List<EvidencePack.RequestResult> requestResults = new ArrayList<>();
        for (InvestigationPlan.EvidenceRequest request : plan.evidenceRequests()) {
            if (request == null) {
                continue;
            }
            EvidenceRequestDimensionContract.Rejected rejectedDim = null;
            for (EvidenceRequestDimensionContract.Rejected r : dimensionGate.rejected()) {
                if (request.requestId() != null
                        && request.requestId().equals(r.request().requestId())) {
                    rejectedDim = r;
                    break;
                }
            }
            if (rejectedDim != null) {
                requestResults.add(EvidencePack.RequestResult.failure(
                        request.requestId(),
                        request.kind(),
                        request.role(),
                        request.hypothesisId(),
                        request.evidencePurpose(),
                        rejectedDim.reason()));
                continue;
            }
            EvidenceRequestSqlCompiler.CompiledRequest cr = compiledById.get(request.requestId());
            if (cr == null) {
                requestResults.add(EvidencePack.RequestResult.failure(
                        request.requestId(),
                        request.kind(),
                        request.role(),
                        request.hypothesisId(),
                        request.evidencePurpose(),
                        "evidence request was not compiled"));
                continue;
            }
            if (!cr.ok()) {
                requestResults.add(EvidencePack.RequestResult.failure(
                        request.requestId(),
                        request.kind(),
                        request.role(),
                        request.hypothesisId(),
                        request.evidencePurpose(),
                        cr.error()));
                continue;
            }
            QueryResult result = byKey.get(cr.spec().key());
            List<Map<String, Object>> rows = result != null && result.rows() != null
                    ? WarehouseRowNormalizer.normalizeRows(result.rows())
                    : List.of();
            pathTrace.step(
                    "warehouse_rows",
                    cr.request().requestId() + " key=" + cr.spec().key() + " rows=" + rows.size());
            requestResults.add(EvidencePack.RequestResult.success(
                    request.requestId(),
                    request.kind(),
                    request.role(),
                    request.hypothesisId(),
                    request.evidencePurpose(),
                    List.of(cr.spec().key()),
                    rows,
                    cr.spec().sql()));
        }

        recordInvestigationWarehouseFacts(runId, question, tableRef, compiled, byKey, pathTrace);

        pathTrace.step("assemble_pack", "building EvidencePack");
        EvidencePack pack = evidencePackAssembler.assemble(
                question, plan, tableRef, requestResults);
        pathTrace.step("render_narrative", "NarrativeRenderer.render + toResult");
        NarrativeOutput narrative = narrativeRenderer.render(pack);
        DecisionRunResult result = narrativeRenderer.toResult(runId, pack, narrative);

        if (pathTrace.snapshot().get("bigquery_errors") instanceof List<?> errs && !errs.isEmpty()) {
            pathTrace.setRootCauseSummary(
                    "BigQuery execution error(s) during Phase 2 evidence batch — see bigquery_errors");
        } else {
            pathTrace.setRootCauseSummary("Phase 2 investigation path completed without runtime exception");
        }
        pathTrace.writeToFile(safeFileHint(question, runId));
        pathTrace.logSummary();
        recordTraceFacts(runId, pathTrace);
        return result;
    }

    private void recordInvestigationWarehouseFacts(
            String runId,
            String question,
            String tableRef,
            EvidenceRequestSqlCompiler.CompiledProgram compiled,
            Map<String, QueryResult> byKey,
            Phase2InvestigationPathTrace pathTrace
    ) {
        UUID id = parseRunId(runId);
        if (id == null || diagnosticSession == null) {
            return;
        }
        StringBuilder sqlJoined = new StringBuilder();
        int totalRows = 0;
        List<Map<String, Object>> sampleRows = new ArrayList<>();
        List<Map<String, Object>> perRequest = new ArrayList<>();
        for (EvidenceRequestSqlCompiler.CompiledRequest cr : compiled.requests()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("request_id", cr.request().requestId());
            entry.put("kind", cr.request().kind());
            entry.put("ok", cr.ok());
            if (!cr.ok()) {
                entry.put("error", cr.error());
                perRequest.add(entry);
                continue;
            }
            entry.put("sql", cr.spec().sql());
            if (sqlJoined.length() > 0) {
                sqlJoined.append("\n;\n");
            }
            sqlJoined.append("-- ").append(cr.request().requestId())
                    .append(" [").append(cr.request().kind()).append("]\n")
                    .append(cr.spec().sql());
            QueryResult qr = byKey.get(cr.spec().key());
            int rowCount = qr != null && qr.rows() != null ? qr.rows().size() : 0;
            totalRows += rowCount;
            entry.put("row_count", rowCount);
            entry.put("rows", qr != null && qr.rows() != null
                    ? WarehouseRowNormalizer.normalizeRows(qr.rows())
                    : List.of());
            perRequest.add(entry);
            if (qr != null && qr.rows() != null) {
                for (Map<String, Object> row : qr.rows()) {
                    if (sampleRows.size() >= 20) {
                        break;
                    }
                    Map<String, Object> tagged = new LinkedHashMap<>(
                            WarehouseRowNormalizer.normalizeRow(row));
                    tagged.put("_request_id", cr.request().requestId());
                    sampleRows.add(tagged);
                }
            }
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("question", question != null ? question : "");
        facts.put("served_path", "investigation");
        facts.put("planner_mode", "gpt");
        facts.put("materialized_query", "INVESTIGATION_BATCH:" + tableRef);
        facts.put("generated_sql", sqlJoined.toString());
        facts.put("warehouse_row_count", totalRows);
        facts.put("sample_rows", sampleRows);
        facts.put("investigation_request_executions", perRequest);
        facts.put("resolved_metric", pathTrace.snapshot().get("resolved_metric"));
        facts.put("resolved_dimension", pathTrace.snapshot().get("resolved_dimension"));
        facts.put("investigation_plan", pathTrace.snapshot().get("investigation_plan"));
        if (pathTrace.snapshot().get("bigquery_error") != null) {
            facts.put("bigquery_error", pathTrace.snapshot().get("bigquery_error"));
        }
        facts.put("phase2_trace", pathTrace.snapshot());
        diagnosticSession.recordWarehouseFacts(id, facts);
    }

    private void recordTraceFacts(String runId, Phase2InvestigationPathTrace pathTrace) {
        UUID id = parseRunId(runId);
        if (id == null || diagnosticSession == null || pathTrace == null) {
            return;
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("phase2_trace", pathTrace.snapshot());
        if (pathTrace.snapshot().get("runtime_exception_stack_trace") != null) {
            facts.put("runtime_exception_stack_trace",
                    pathTrace.snapshot().get("runtime_exception_stack_trace"));
        }
        if (pathTrace.snapshot().get("exact_root_cause") != null) {
            facts.put("exact_root_cause", pathTrace.snapshot().get("exact_root_cause"));
        }
        if (pathTrace.snapshot().get("bigquery_error") != null) {
            facts.put("bigquery_error", pathTrace.snapshot().get("bigquery_error"));
        }
        diagnosticSession.recordWarehouseFacts(id, facts);
    }

    private static String classifyRootCause(Throwable ex, Phase2InvestigationPathTrace pathTrace) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (pathTrace.snapshot().get("bigquery_errors") instanceof List<?> errs && !errs.isEmpty()) {
            return "Runtime aborted after/near BigQuery failure(s): " + root.getClass().getName()
                    + ": " + root.getMessage();
        }
        return root.getClass().getName() + ": " + root.getMessage()
                + " at Phase 2 investigation path (see runtime_exception_stack_trace)";
    }

    private static String primaryMetric(InvestigationPlan plan) {
        if (plan.phenomenon() != null && plan.phenomenon().measure() != null
                && plan.phenomenon().measure().column() != null
                && !plan.phenomenon().measure().column().isBlank()) {
            return plan.phenomenon().measure().aggregation() + "("
                    + plan.phenomenon().measure().column() + ")";
        }
        for (InvestigationPlan.EvidenceRequest req : plan.evidenceRequests()) {
            if (req.measure() != null && req.measure().column() != null
                    && !req.measure().column().isBlank()) {
                return req.measure().aggregation() + "(" + req.measure().column() + ")";
            }
        }
        return null;
    }

    private static String primaryDimension(InvestigationPlan plan) {
        for (InvestigationPlan.EvidenceRequest req : plan.evidenceRequests()) {
            if (req.dimension() != null && !req.dimension().isBlank()) {
                return req.dimension();
            }
        }
        return null;
    }

    private static Map<String, Object> planToMap(InvestigationPlan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (plan.phenomenon() != null) {
            Map<String, Object> ph = new LinkedHashMap<>();
            ph.put("subject", plan.phenomenon().subject());
            if (plan.phenomenon().measure() != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("column", plan.phenomenon().measure().column());
                m.put("aggregation", plan.phenomenon().measure().aggregation());
                m.put("grain", plan.phenomenon().measure().grain());
                ph.put("measure", m);
            }
            out.put("phenomenon", ph);
        }
        out.put("scope_filters", plan.scopeFilters());
        out.put("semantic_confidence", plan.semanticConfidence());
        List<Map<String, Object>> hypotheses = new ArrayList<>();
        if (plan.hypotheses() != null) {
            for (InvestigationPlan.Hypothesis h : plan.hypotheses()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", h.id());
                row.put("claim", h.claim());
                row.put("status", h.status());
                hypotheses.add(row);
            }
        }
        out.put("hypotheses", hypotheses);
        if (plan.insufficiency() != null) {
            Map<String, Object> insuff = new LinkedHashMap<>();
            insuff.put("answerable", plan.insufficiency().answerable());
            insuff.put("unknowns", plan.insufficiency().unknowns());
            insuff.put("blocked_reasons", plan.insufficiency().blockedReasons());
            out.put("insufficiency", insuff);
        }
        List<Map<String, Object>> reqs = new ArrayList<>();
        for (InvestigationPlan.EvidenceRequest req : plan.evidenceRequests()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("request_id", req.requestId());
            r.put("kind", req.kind());
            r.put("role", req.role());
            r.put("hypothesis_id", req.hypothesisId());
            r.put("evidence_purpose", req.evidencePurpose());
            r.put("dimension", req.dimension());
            if (req.measure() != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("column", req.measure().column());
                m.put("aggregation", req.measure().aggregation());
                r.put("measure", m);
            }
            reqs.add(r);
        }
        out.put("evidence_requests", reqs);
        return out;
    }

    private static String safeFileHint(String question, String runId) {
        String q = question != null ? question : "question";
        String id = runId != null ? runId : "run";
        return q + "__" + id;
    }

    private ApprovedCatalogueSnapshot loadCatalogue(String tenantId, RegistryResolutionBundle bundle) {
        try {
            String snapshotJson = catalogueApprovalService.getApprovedSnapshot(tenantId);
            JsonNode node = PLAN_JSON.readTree(snapshotJson);
            return SemanticCatalogueFactory.catalogueFrom(node, bundle);
        } catch (Exception e) {
            log.warn("[phase2-investigation] catalogue snapshot unavailable for tenant={}, "
                    + "field validation uses bundle fallback: {}", tenantId, e.getMessage());
            return SemanticCatalogueFactory.catalogueFrom(null, bundle);
        }
    }

    private String resolveTableRef(GptPlanningOutcome outcome, RegistryResolutionBundle bundle) {
        if (outcome != null && outcome.analysisPlan() != null
                && outcome.analysisPlan().tableRef() != null) {
            return outcome.analysisPlan().tableRef();
        }
        if (bundle != null && bundle.entities() != null && !bundle.entities().isEmpty()) {
            return bundle.entities().getFirst().tableRef();
        }
        return null;
    }

    private Map<String, QueryResult> toResultMap(List<QueryResult> results) {
        Map<String, QueryResult> out = new LinkedHashMap<>();
        if (results == null) {
            return out;
        }
        for (QueryResult r : results) {
            if (r != null && r.key() != null) {
                out.put(r.key(), r);
            }
        }
        return out;
    }

    private UUID parseRunId(String runId) {
        if (runId == null) {
            return null;
        }
        try {
            return UUID.fromString(runId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
