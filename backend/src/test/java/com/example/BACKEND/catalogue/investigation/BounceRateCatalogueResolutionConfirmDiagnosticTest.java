package com.example.BACKEND.catalogue.investigation;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.BACKEND.catalogue.decision.api.DecisionResponseMapper;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionExecutionContext;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import com.example.BACKEND.catalogue.decision.runtime.DecisionRuntime;
import com.example.BACKEND.catalogue.entity.CatalogueSnapshotEntity;
import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.example.BACKEND.catalogue.repository.CatalogueSnapshotRepository;
import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import com.example.BACKEND.catalogue.semantic.phase2.GptSemanticPlanningOrchestrator;
import com.example.BACKEND.catalogue.semantic.phase2.GptStructuredSemanticPlanner;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticCatalogueFactory;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.example.BACKEND.identity.auth.AuthContext;
import com.example.BACKEND.identity.auth.AuthContextHolder;
import com.example.BACKEND.tenancy.TenantContext;
import com.example.BACKEND.tenancy.TenantContextHolder;
import com.example.BACKEND.tenancy.TenantRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
/**
 * READ-ONLY root-cause confirmation for bounce-rate dimension failure.
 * Does not change production behavior.
 *
 * Enable:
 *   -Dkontexa.live.bounce.confirm=true
 * Optional:
 *   -Dkontexa.live.ga.tenant=&lt;workspace-slug&gt;
 *   -Dkontexa.live.catalogue.id=&lt;catalogue-id&gt;
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "kontexa.live.bounce.confirm", matches = "true")
class BounceRateCatalogueResolutionConfirmDiagnosticTest {

    private static final String QUESTION = "Why is bounce rate high?";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Autowired DecisionRuntime decisionRuntime;
    @Autowired DecisionResponseMapper responseMapper;
    @Autowired CatalogueApprovalService approvalService;
    @Autowired CatalogueSnapshotRepository snapshotRepo;
    @Autowired JdbcTemplate jdbc;

    private final List<ListAppender<ILoggingEvent>> appenders = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (ListAppender<ILoggingEvent> a : appenders) {
            a.stop();
            ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(a);
        }
        appenders.clear();
        AuthContextHolder.clear();
        TenantContextHolder.clear();
        Phase2InvestigationPathTrace.clear();
    }

    @Test
    @Transactional
    @DisplayName("Confirm catalogue resolution vs dimension-null path for bounce-rate WHY")
    void confirmCatalogueResolutionHypothesis() throws Exception {
        ListAppender<ILoggingEvent> plannerLogs = attach(GptStructuredSemanticPlanner.class);
        ListAppender<ILoggingEvent> orchLogs = attach(GptSemanticPlanningOrchestrator.class);
        ListAppender<ILoggingEvent> runtimeLogs = attach(InvestigationRuntime.class);

        TenantHit tenant = resolveTenant();
        if (tenant == null) {
            System.out.println("DIAG_ABORT: no approved catalogue / workspace found");
            return;
        }

        TenantContext tenantCtx = new TenantContext(
                tenant.workspaceId(),
                tenant.slug(),
                UUID.randomUUID(),
                "bounce-confirm@local",
                "Bounce Confirm",
                TenantRole.ADMIN,
                UUID.randomUUID(),
                "test");
        TenantContextHolder.set(tenantCtx);
        AuthContextHolder.set(new AuthContext(
                tenantCtx.sessionId(),
                tenantCtx.userId(),
                tenantCtx.tenantId(),
                tenantCtx.legacyTenantKey(),
                tenantCtx.email(),
                tenantCtx.displayName(),
                "ADMIN",
                "test"));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("question", QUESTION);
        report.put("tenant_context_tenant_id", tenantCtx.tenantId().toString());
        report.put("tenant_context_legacy_tenant_key", tenantCtx.legacyTenantKey());

        // ---- A/B/C catalogue resolution comparison ----
        List<ClientCatalogueEntity> uiList = approvalService.listForTenant(tenantCtx);
        Long forcedCatalogueId = parseLongProp("kontexa.live.catalogue.id");
        ClientCatalogueEntity uiCatalogue = pickCatalogue(uiList, forcedCatalogueId);
        report.put("ui_list_count", uiList.size());
        report.put("ui_catalogue_ids", uiList.stream().map(ClientCatalogueEntity::getId).toList());

        Map<String, Object> uiBlock = new LinkedHashMap<>();
        if (uiCatalogue != null) {
            uiBlock.put("id", uiCatalogue.getId());
            uiBlock.put("client_id", uiCatalogue.getClientId());
            uiBlock.put("tenant_id", uiCatalogue.getTenantId() != null
                    ? uiCatalogue.getTenantId().toString() : null);
            uiBlock.put("status", uiCatalogue.getStatus());
            uiBlock.put("schema_name", uiCatalogue.getSchemaName());
            uiBlock.put("database_name", uiCatalogue.getDatabaseName());
        } else {
            uiBlock.put("id", null);
            uiBlock.put("error", "no catalogue in listForTenant");
        }
        report.put("ui_catalogue", uiBlock);

        String runtimeLookupKey = tenantCtx.legacyTenantKey();
        report.put("runtime_lookup_key", runtimeLookupKey);

        Optional<CatalogueSnapshotEntity> byClientId = snapshotRepo.findByClientId(runtimeLookupKey);
        Optional<CatalogueSnapshotEntity> byTenantId = snapshotRepo.findByTenantId(tenantCtx.tenantId());

        boolean runtimeFound;
        String runtimeSnapshotJson = null;
        String runtimeError = null;
        try {
            runtimeSnapshotJson = approvalService.getApprovedSnapshot(runtimeLookupKey);
            runtimeFound = true;
        } catch (Exception e) {
            runtimeFound = false;
            runtimeError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        Map<String, Object> runtimeSnap = snapshotMeta(byClientId.orElse(null));
        runtimeSnap.put("getApprovedSnapshot_found", runtimeFound);
        runtimeSnap.put("getApprovedSnapshot_error", runtimeError);
        runtimeSnap.put("json_length", runtimeSnapshotJson != null ? runtimeSnapshotJson.length() : 0);
        report.put("runtime_snapshot_by_client_id", runtimeSnap);

        Map<String, Object> tenantSnap = snapshotMeta(byTenantId.orElse(null));
        tenantSnap.put("findByTenantId_found", byTenantId.isPresent());
        report.put("tenant_id_snapshot", tenantSnap);

        boolean sameSnapshot = byClientId.isPresent() && byTenantId.isPresent()
                && byClientId.get().getId().equals(byTenantId.get().getId());
        report.put("same_snapshot_client_vs_tenant_lookup", sameSnapshot);
        report.put("same_snapshot_detail", Map.of(
                "client_id_lookup_snapshot_pk", byClientId.map(CatalogueSnapshotEntity::getId).orElse(null),
                "tenant_id_lookup_snapshot_pk", byTenantId.map(CatalogueSnapshotEntity::getId).orElse(null),
                "client_id_lookup_catalogue_id", byClientId.map(CatalogueSnapshotEntity::getCatalogueId).orElse(null),
                "tenant_id_lookup_catalogue_id", byTenantId.map(CatalogueSnapshotEntity::getCatalogueId).orElse(null)
        ));

        // Approval/upsert path equivalence: findSnapshotForCatalogue logic mirrored
        Optional<CatalogueSnapshotEntity> approvalPath = Optional.empty();
        if (uiCatalogue != null) {
            if (uiCatalogue.getTenantId() != null) {
                approvalPath = snapshotRepo.findByTenantId(uiCatalogue.getTenantId());
            }
            if (approvalPath.isEmpty()) {
                approvalPath = snapshotRepo.findByClientId(uiCatalogue.getClientId());
            }
        }
        report.put("approval_upsert_path_snapshot", snapshotMeta(approvalPath.orElse(null)));
        report.put("ui_vs_runtime_same_catalogue_id",
                uiCatalogue != null && byClientId.isPresent()
                        && uiCatalogue.getId().equals(byClientId.get().getCatalogueId()));
        report.put("ui_vs_approval_same_snapshot_pk",
                uiCatalogue != null && approvalPath.isPresent() && byClientId.isPresent()
                        && approvalPath.get().getId().equals(byClientId.get().getId()));

        // ---- Live decision run ----
        Phase2InvestigationPathTrace.clear();
        DecisionExecutionContext ctx = DecisionRuntime.contextFrom(
                tenantCtx.legacyTenantKey(), QUESTION, Map.of());
        report.put("decision_run_id", ctx.runId().toString());

        Map<String, Object> runOut = new LinkedHashMap<>();
        try {
            DecisionRunResult result = decisionRuntime.execute(ctx);
            Map<String, Object> response = responseMapper.toRunResponse(ctx.runId(), result, Map.of());
            runOut.put("status", "OK");
            runOut.put("served_path", response.get("served_path"));
            runOut.put("planner_execution_mode", response.get("planner_execution_mode"));
            if (response.get("investigation") instanceof Map<?, ?> inv) {
                Map<String, Object> invSlim = new LinkedHashMap<>();
                invSlim.put("headline", inv.get("headline"));
                invSlim.put("coverage", inv.get("coverage"));
                invSlim.put("evidence_validation", inv.get("evidence_validation"));
                Object reqs = inv.get("request_results");
                if (reqs == null) {
                    reqs = inv.get("evidence_requests");
                }
                invSlim.put("request_results_or_evidence", summarizeFailures(reqs));
                runOut.put("investigation_slim", invSlim);
            }
        } catch (Exception ex) {
            runOut.put("status", "ERROR");
            runOut.put("error", ex.getClass().getSimpleName() + ": " + safeMsg(ex.getMessage()));
        }
        report.put("decision_run", runOut);

        boolean fallbackLogged = orchLogs.list.stream().anyMatch(e -> {
            String m = e.getFormattedMessage();
            return m != null && (m.contains("catalogue snapshot unavailable")
                    || m.contains("using bundle fallback")
                    || m.contains("field validation uses bundle fallback"));
        });
        boolean runtimeFallbackLogged = runtimeLogs.list.stream().anyMatch(e -> {
            String m = e.getFormattedMessage();
            return m != null && m.contains("catalogue snapshot unavailable");
        });
        report.put("orchestrator_fallback_log_seen", fallbackLogged);
        report.put("investigation_runtime_fallback_log_seen", runtimeFallbackLogged);

        // ---- Parse raw GPT JSON from planner logs ----
        String rawGpt = extractRawGptJson(plannerLogs);
        report.put("raw_gpt_json_captured", rawGpt != null);
        JsonNode rawRoot = rawGpt != null ? JSON.readTree(rawGpt) : null;

        List<String> gptRawDimensions = new ArrayList<>();
        if (rawRoot != null && rawRoot.path("dimensions").isArray()) {
            for (JsonNode d : rawRoot.path("dimensions")) {
                gptRawDimensions.add(d.asText(null));
            }
        }
        report.put("gpt_raw_dimensions", gptRawDimensions);

        // Catalogue actually used by reasoning: prefer runtime snapshot JSON; else null→bundle
        boolean fullApprovedLoaded = runtimeFound && runtimeSnapshotJson != null
                && !runtimeSnapshotJson.isBlank() && !fallbackLogged;
        ApprovedCatalogueSnapshot semanticCatalogue;
        if (runtimeFound && runtimeSnapshotJson != null) {
            JsonNode node = JSON.readTree(runtimeSnapshotJson);
            semanticCatalogue = SemanticCatalogueFactory.catalogueFrom(node, emptyBundle());
            report.put("semantic_catalogue_source", "approved_snapshot_json");
        } else {
            semanticCatalogue = SemanticCatalogueFactory.catalogueFrom(null, emptyBundle());
            report.put("semantic_catalogue_source", "null_node_bundle_fallback");
            fullApprovedLoaded = false;
        }
        // If fallback logged during run, mark incomplete even if snapshot exists for key
        if (fallbackLogged || runtimeFallbackLogged) {
            fullApprovedLoaded = false;
            report.put("semantic_catalogue_source_override", "fallback_logged_during_run");
        }

        List<String> availableDims = semanticCatalogue.dimensionColumns();
        report.put("available_dimensions_count", availableDims.size());
        report.put("available_dimensions_sample", availableDims.stream().limit(40).toList());
        report.put("available_columns_total", semanticCatalogue.columns().size());
        report.put("bindable_leaf_count", semanticCatalogue.bindableLeafColumns().size());

        List<Map<String, Object>> bindResults = new ArrayList<>();
        for (String raw : gptRawDimensions) {
            bindResults.add(diagnoseBind(semanticCatalogue, raw, false));
        }
        // Also bind evidence-request dimensions from raw investigation_plan
        List<Map<String, Object>> evidenceBefore = new ArrayList<>();
        List<Map<String, Object>> evidenceAfter = new ArrayList<>();
        List<String> semanticDimsAfterBind = new ArrayList<>();
        for (Map<String, Object> br : bindResults) {
            if (br.get("bound") != null) {
                semanticDimsAfterBind.add(String.valueOf(br.get("bound")));
            }
        }
        report.put("bindcolumn_results", bindResults);
        report.put("semantic_plan_dimensions_after_bind_recomputed", semanticDimsAfterBind);

        String afterBindFromLog = extractAfterBindDimensions(plannerLogs);
        report.put("structured_plan_dimensions_from_log", afterBindFromLog);

        if (rawRoot != null) {
            JsonNode invPlan = rawRoot.path("investigation_plan");
            if (!invPlan.isMissingNode() && !invPlan.isNull()) {
                InvestigationPlanParser planParser = new InvestigationPlanParser();
                InvestigationObjectiveParser objectiveParser = new InvestigationObjectiveParser();
                InvestigationPlan parsed = planParser.parse(invPlan, objectiveParser);
                if (parsed != null && parsed.evidenceRequests() != null) {
                    for (InvestigationPlan.EvidenceRequest req : parsed.evidenceRequests()) {
                        Map<String, Object> before = new LinkedHashMap<>();
                        before.put("request_id", req.requestId());
                        before.put("kind", req.kind());
                        before.put("measure", req.measure() != null ? req.measure().column() : null);
                        before.put("dimension", req.dimension());
                        evidenceBefore.add(before);
                    }
                    StructuredSemanticPlan semanticForBinder =
                            buildMinimalSemanticPlan(rawRoot, semanticDimsAfterBind);
                    InvestigationPlan bound = InvestigationPlanMetricBinder.bind(parsed, semanticForBinder);
                    for (InvestigationPlan.EvidenceRequest req : bound.evidenceRequests()) {
                        Map<String, Object> after = new LinkedHashMap<>();
                        after.put("request_id", req.requestId());
                        after.put("kind", req.kind());
                        after.put("measure", req.measure() != null ? req.measure().column() : null);
                        after.put("dimension", req.dimension());
                        evidenceAfter.add(after);
                    }
                }
            }
        }
        report.put("evidence_dimensions_before_binder", evidenceBefore);
        report.put("evidence_dimensions_after_binder", evidenceAfter);

        boolean anyEvidenceDimNullAfter = evidenceAfter.stream()
                .anyMatch(e -> requiresDimension(String.valueOf(e.get("kind")))
                        && (e.get("dimension") == null
                        || String.valueOf(e.get("dimension")).isBlank()));
        String exactReason;
        if (evidenceAfter.isEmpty() && evidenceBefore.isEmpty()) {
            exactReason = "No investigation_plan evidence_requests captured from GPT raw JSON "
                    + "(planner may not have emitted INVESTIGATION plan, or raw JSON not logged).";
        } else if (anyEvidenceDimNullAfter) {
            boolean topEmpty = semanticDimsAfterBind.isEmpty();
            boolean gptEmpty = gptRawDimensions.isEmpty()
                    || gptRawDimensions.stream().allMatch(d -> d == null || d.isBlank());
            boolean allRejected = !gptRawDimensions.isEmpty() && semanticDimsAfterBind.isEmpty();
            if (gptEmpty) {
                exactReason = "GPT left top-level dimensions empty/null AND evidence_requests[*].dimension "
                        + "null/blank for dimension-requiring kinds; binder had nothing to propagate.";
            } else if (allRejected) {
                exactReason = "GPT proposed top-level dimensions but bindColumn rejected all of them "
                        + "against the loaded catalogue; binder resolveBoundDimension returned null; "
                        + "evidence dimensions stayed null.";
            } else if (topEmpty) {
                exactReason = "After bind, StructuredSemanticPlan.dimensions empty; binder no-op; "
                        + "evidence_requests still had null dimension.";
            } else {
                exactReason = "Top-level dimensions bound but evidence still null after binder — "
                        + "binder propagation failure (unexpected with current binder).";
            }
        } else {
            exactReason = "Evidence dimensions non-null after binder recompute — "
                    + "requireDimension should NOT receive null for this captured plan. "
                    + "Check live compile errors in investigation_slim.";
        }
        report.put("exact_reason_requireDimension_null", exactReason);
        report.put("full_approved_catalogue_loaded_by_reasoning", fullApprovedLoaded);
        report.put("fallback_catalogue_used", !fullApprovedLoaded);

        // Classification helper fields
        report.put("hypothesis_A_lookup_mismatch",
                !runtimeFound || !sameSnapshot || (uiCatalogue != null && byClientId.isPresent()
                        && !uiCatalogue.getId().equals(byClientId.get().getCatalogueId())));

        Path out = Path.of("target", "phase2-investigation-traces",
                "bounce-rate-catalogue-resolution-confirm.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, JSON.writeValueAsString(report));
        System.out.println("========== BOUNCE RATE CATALOGUE RESOLUTION CONFIRM ==========");
        System.out.println(JSON.writeValueAsString(report));
        System.out.println("Wrote " + out.toAbsolutePath());
    }

    private TenantHit resolveTenant() {
        String forced = System.getProperty("kontexa.live.ga.tenant");
        if (forced != null && !forced.isBlank()) {
            UUID ws = findWorkspaceId(forced.trim()).orElse(UUID.randomUUID());
            return new TenantHit(ws, forced.trim(), "forced");
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    """
                    SELECT c.id AS catalogue_id, c.client_id, c.tenant_id::text AS tenant_id,
                           c.status, c.schema_name
                    FROM client_catalogues c
                    WHERE UPPER(c.status) = 'APPROVED'
                    ORDER BY c.updated_at DESC NULLS LAST
                    LIMIT 20
                    """);
            System.out.println("APPROVED catalogues: " + rows);
            for (Map<String, Object> row : rows) {
                String clientId = String.valueOf(row.get("client_id"));
                if (mentionsBounceOrGa(clientId)) {
                    UUID ws = findWorkspaceId(clientId).orElseGet(() -> {
                        Object tid = row.get("tenant_id");
                        if (tid != null && !String.valueOf(tid).isBlank()
                                && !"null".equalsIgnoreCase(String.valueOf(tid))) {
                            return UUID.fromString(String.valueOf(tid));
                        }
                        return UUID.randomUUID();
                    });
                    // Prefer workspace slug matching client_id; if catalogue.tenant_id set, use it
                    Object tid = row.get("tenant_id");
                    if (tid != null && !String.valueOf(tid).isBlank()
                            && !"null".equalsIgnoreCase(String.valueOf(tid))) {
                        ws = UUID.fromString(String.valueOf(tid));
                    }
                    Optional<UUID> bySlug = findWorkspaceId(clientId);
                    String slug = clientId;
                    if (bySlug.isPresent()) {
                        ws = bySlug.get();
                    } else {
                        // resolve slug from workspaces by id
                        String resolved = findSlugByWorkspaceId(ws);
                        if (resolved != null) {
                            slug = resolved;
                        }
                    }
                    return new TenantHit(ws, slug, "approved_bounce_or_ga");
                }
            }
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.getFirst();
                String clientId = String.valueOf(row.get("client_id"));
                UUID ws = findWorkspaceId(clientId).orElse(UUID.randomUUID());
                Object tid = row.get("tenant_id");
                if (tid != null && !String.valueOf(tid).isBlank()
                        && !"null".equalsIgnoreCase(String.valueOf(tid))) {
                    ws = UUID.fromString(String.valueOf(tid));
                }
                return new TenantHit(ws, clientId, "first_approved");
            }
        } catch (Exception e) {
            System.out.println("resolveTenant failed: " + e.getMessage());
        }
        return null;
    }

    private boolean mentionsBounceOrGa(String clientId) {
        try {
            Integer n = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM catalogue_columns cc
                    JOIN catalogue_tables ct ON ct.id = cc.table_id
                    JOIN client_catalogues c ON c.id = ct.catalogue_id
                    WHERE c.client_id = ? AND UPPER(c.status)='APPROVED' AND (
                      cc.column_name ILIKE '%bounce%'
                      OR cc.column_name ILIKE '%geoNetwork%'
                      OR cc.column_name ILIKE '%trafficSource%'
                      OR ct.table_name ILIKE '%ga_sessions%'
                      OR ct.table_name ILIKE '%analytics%'
                      OR c.schema_name ILIKE '%google_analytics%'
                      OR c.schema_name ILIKE '%analytics%')
                    """,
                    Integer.class,
                    clientId);
            return n != null && n > 0;
        } catch (Exception e) {
            System.out.println("mentionsBounceOrGa probe failed for " + clientId + ": " + e.getMessage());
            return false;
        }
    }

    private Optional<UUID> findWorkspaceId(String slug) {
        try {
            List<UUID> ids = jdbc.query(
                    "SELECT id FROM workspaces WHERE slug = ? LIMIT 1",
                    (rs, i) -> UUID.fromString(rs.getString(1)),
                    slug);
            return ids.stream().findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String findSlugByWorkspaceId(UUID id) {
        try {
            return jdbc.queryForObject(
                    "SELECT slug FROM workspaces WHERE id = ?::uuid LIMIT 1",
                    String.class,
                    id.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private ClientCatalogueEntity pickCatalogue(List<ClientCatalogueEntity> list, Long forcedId) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        if (forcedId != null) {
            return list.stream().filter(c -> forcedId.equals(c.getId())).findFirst()
                    .orElse(list.getFirst());
        }
        return list.stream()
                .filter(c -> c.getStatus() != null && "APPROVED".equalsIgnoreCase(c.getStatus()))
                .findFirst()
                .orElse(list.getFirst());
    }

    private Map<String, Object> snapshotMeta(CatalogueSnapshotEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (s == null) {
            m.put("found", false);
            return m;
        }
        m.put("found", true);
        m.put("snapshot_pk", s.getId());
        m.put("catalogue_id", s.getCatalogueId());
        m.put("client_id", s.getClientId());
        m.put("tenant_id", s.getTenantId() != null ? s.getTenantId().toString() : null);
        m.put("json_length", s.getCatalogueJson() != null ? s.getCatalogueJson().length() : 0);
        return m;
    }

    private ListAppender<ILoggingEvent> attach(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        appenders.add(appender);
        return appender;
    }

    private String extractRawGptJson(ListAppender<ILoggingEvent> plannerLogs) {
        for (ILoggingEvent e : plannerLogs.list) {
            String m = e.getFormattedMessage();
            if (m != null && m.contains("stage=raw_gpt_json") && m.contains("payload=")) {
                int idx = m.indexOf("payload=");
                return m.substring(idx + "payload=".length()).trim();
            }
        }
        return null;
    }

    private String extractAfterBindDimensions(ListAppender<ILoggingEvent> plannerLogs) {
        for (ILoggingEvent e : plannerLogs.list) {
            String m = e.getFormattedMessage();
            if (m != null && m.contains("stage=structured_semantic_plan") && m.contains("payload=")) {
                int idx = m.indexOf("payload=");
                return m.substring(idx + "payload=".length()).trim();
            }
        }
        // Also collect bindColumn_failed lines
        List<String> fails = new ArrayList<>();
        for (ILoggingEvent e : plannerLogs.list) {
            String m = e.getFormattedMessage();
            if (m != null && m.contains("stage=bindColumn_failed")) {
                fails.add(m);
            }
        }
        if (!fails.isEmpty()) {
            return "bind_failures=" + fails;
        }
        return null;
    }

    /**
     * Mirrors GptStructuredSemanticPlanner.bindColumn rules for diagnostics only.
     */
    private Map<String, Object> diagnoseBind(
            ApprovedCatalogueSnapshot catalogue, String col, boolean metricOk) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("input", col);
        if (col == null || col.isBlank()) {
            out.put("bound", null);
            out.put("rejection", "blank_or_null");
            return out;
        }
        if (!catalogue.hasColumn(col)) {
            out.put("bound", null);
            out.put("rejection", diagnoseMissing(catalogue, col));
            return out;
        }
        if (!catalogue.isBindableLeaf(col)) {
            out.put("bound", null);
            out.put("rejection", "not_bindable_leaf (container/parent path)");
            return out;
        }
        String bound = catalogue.canonicalColumnName(col);
        out.put("bound", bound);
        out.put("rejection", null);
        if (metricOk) {
            out.put("note", "metricOk path");
        }
        return out;
    }

    private String diagnoseMissing(ApprovedCatalogueSnapshot catalogue, String col) {
        String trimmed = col.trim();
        if (trimmed.matches("(?i)^[A-Z_]+\\(.+\\)$")) {
            return "wrapper_expression_not_catalogue_leaf";
        }
        int dot = trimmed.indexOf('.');
        if (dot > 0) {
            String maybe = trimmed.substring(dot + 1);
            if (catalogue.hasColumn(maybe)) {
                return "table_qualified_path_mismatch; catalogue leaf is '" + maybe + "'";
            }
        }
        return "exact_string_mismatch; no catalogue column named '" + trimmed + "'";
    }

    private StructuredSemanticPlan buildMinimalSemanticPlan(
            JsonNode rawRoot,
            List<String> boundDims
    ) {
        String metric = rawRoot.path("metric").asText(null);
        // Binder only needs metric string + dimensions (+ investigation plan node unused by binder).
        return new StructuredSemanticPlan(
                rawRoot.path("intent").asText("WHY"),
                metric,
                null,
                boundDims,
                List.of(),
                new StructuredSemanticPlan.SemanticAggregations(
                        rawRoot.path("aggregations").path("primary").asText("SUM"),
                        null),
                null,
                null,
                null,
                null,
                rawRoot.path("confidence").asDouble(0.5),
                rawRoot.path("reasoning").asText(""),
                List.of(),
                StructuredSemanticPlan.MODE_INVESTIGATION,
                null,
                rawRoot.path("investigation_objective"),
                rawRoot.path("investigation_plan"));
    }

    private boolean requiresDimension(String kind) {
        if (kind == null) return false;
        String k = kind.trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "metric_by_dimension", "distribution", "correlation",
                 "rank", "top_n", "bottom_n", "share", "concentration" -> true;
            default -> false;
        };
    }

    private Object summarizeFailures(Object reqs) {
        if (!(reqs instanceof List<?> list)) {
            return reqs;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("request_id", m.get("request_id") != null ? m.get("request_id") : m.get("id"));
            row.put("kind", m.get("kind"));
            row.put("dimension", m.get("dimension"));
            row.put("error", m.get("error") != null ? m.get("error") : m.get("failure_reason"));
            row.put("ok", m.get("ok"));
            out.add(row);
        }
        return out;
    }

    private static com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle emptyBundle() {
        return new com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle(
                List.of(), List.of(), List.of(), null);
    }

    private static Long parseLongProp(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) return null;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String safeMsg(String msg) {
        if (msg == null) return null;
        // Strip anything that looks like PEM / JSON private key material
        String s = msg;
        if (s.contains("BEGIN PRIVATE KEY") || s.contains("private_key")) {
            return "(redacted: credential-like message)";
        }
        if (s.length() > 800) {
            return s.substring(0, 800) + "...";
        }
        return s;
    }

    private record TenantHit(UUID workspaceId, String slug, String how) {}
}
