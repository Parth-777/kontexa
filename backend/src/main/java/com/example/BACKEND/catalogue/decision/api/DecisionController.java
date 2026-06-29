package com.example.BACKEND.catalogue.decision.api;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.runtime.DecisionRuntime;
import com.example.BACKEND.catalogue.decision.runtime.DecisionRuntimeException;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanningProperties;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticShadowResponseMapper;
import com.example.BACKEND.identity.auth.AuthContext;
import com.example.BACKEND.identity.auth.AuthContextHolder;
import com.example.BACKEND.identity.auth.ForbiddenException;
import com.example.BACKEND.identity.auth.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Decision runtime REST API — additive endpoints, no existing APIs changed.
 *
 * Base path: /api/decision/v1
 *
 *   POST  /api/decision/v1/run         — execute a decision run (sync, Phase 1)
 *   GET   /api/decision/v1/run/{runId} — poll run status
 */
@RestController
@RequestMapping("/api/decision/v1")
public class DecisionController {

    private static final Logger log = LoggerFactory.getLogger(DecisionController.class);

    private final DecisionRuntime runtime;
    private final DecisionResponseMapper responseMapper;
    private final SemanticPlanningProperties plannerProperties;
    private final SemanticShadowResponseMapper shadowResponseMapper;

    public DecisionController(
            DecisionRuntime runtime,
            DecisionResponseMapper responseMapper,
            SemanticPlanningProperties plannerProperties,
            SemanticShadowResponseMapper shadowResponseMapper
    ) {
        this.runtime = runtime;
        this.responseMapper = responseMapper;
        this.plannerProperties = plannerProperties;
        this.shadowResponseMapper = shadowResponseMapper;
    }

    /**
     * Execute a decision run and return the full executive insight output.
     *
     * Tenant isolation: the workspace is resolved EXCLUSIVELY from the
     * authenticated session ({@link AuthContext}). The {@code X-Client-Id}
     * header and body {@code tenantId} are NOT trusted for tenant selection;
     * if supplied and they disagree with the authenticated workspace the
     * request is rejected with 403 (no silent tenant switching).
     *
     * Request:  { "question": "..." }   (tenant comes from the session)
     * Response: full InsightOutput with strategic implications, risks, causes.
     */
    @PostMapping("/run")
    public ResponseEntity<?> executeRun(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Client-Id", required = false) String headerTenantId
    ) {
        String question = stringField(body, "question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        // Workspace identity comes ONLY from the authenticated session.
        AuthContext auth = AuthContextHolder.get();
        if (auth == null || !auth.hasWorkspace()
                || auth.workspaceSlug() == null || auth.workspaceSlug().isBlank()) {
            throw new UnauthorizedException("Authentication required");
        }
        String tenantId = auth.workspaceSlug();

        // Client-supplied tenant hints may NEVER change the tenant. If present
        // and not matching the authenticated workspace, fail closed with 403.
        String bodyTenant = stringField(body, "tenantId");
        boolean headerMismatch = headerTenantId != null && !headerTenantId.isBlank()
                && !headerTenantId.trim().equals(tenantId);
        boolean bodyMismatch = bodyTenant != null && !bodyTenant.isBlank()
                && !bodyTenant.equals(tenantId);
        if (headerMismatch || bodyMismatch) {
            log.warn("[decision-api] tenant mismatch blocked — authenticated='{}' header='{}' body='{}'",
                    tenantId, headerTenantId, bodyTenant);
            throw new ForbiddenException(
                    "Tenant mismatch between request and authenticated workspace.");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = body.containsKey("meta")
                ? (Map<String, Object>) body.get("meta") : Map.of();

        DecisionExecutionContext ctx = DecisionRuntime.contextFrom(tenantId, question, meta);
        log.info("[decision-api] POST /run  runId={}  tenant={} (source=auth_context)", ctx.runId(), tenantId);

        try {
            DecisionRunResult result = runtime.execute(ctx);
            return ResponseEntity.ok(responseMapper.toRunResponse(ctx.runId(), result, meta));
        } catch (DecisionRuntimeException ex) {
            log.error("[decision-api] run failed  runId={}  error={}", ctx.runId(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("runId", ctx.runId().toString(), "error", ex.getMessage()));
        }
    }

    /** Planner mode and shadow rollout status for the UI. */
    @GetMapping("/planner/status")
    public ResponseEntity<Map<String, Object>> plannerStatus() {
        return ResponseEntity.ok(shadowResponseMapper.plannerStatus(plannerProperties));
    }

    /** Poll run status by ID. */
    @GetMapping("/run/{runId}")
    public ResponseEntity<?> runStatus(@PathVariable String runId) {
        try {
            UUID id = UUID.fromString(runId);
            ExecutionRun run = runtime.status(id);
            return ResponseEntity.ok(toStatusResponse(run));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid runId format"));
        } catch (DecisionRuntimeException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
        }
    }

    // ─── response shapers ─────────────────────────────────────────────────

    private Map<String, Object> toStatusResponse(ExecutionRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId",      run.runId().toString());
        m.put("state",      run.state().name());
        m.put("stage",      run.stage().name());
        m.put("startedAt",  run.startedAt()  != null ? run.startedAt().toString()  : null);
        m.put("finishedAt", run.finishedAt() != null ? run.finishedAt().toString() : null);
        if (run.errorMessage() != null) m.put("error", run.errorMessage());
        var trace = runtime.executionTrace(run.runId());
        if (trace != null && !trace.steps().isEmpty()) {
            m.put("execution_trace", trace.toMap());
        }
        return m;
    }

    private String stringField(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : v.toString().trim();
    }
}
