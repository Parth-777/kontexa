package com.example.BACKEND.catalogue.decision.api;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.runtime.DecisionRuntime;
import com.example.BACKEND.catalogue.decision.runtime.DecisionRuntimeException;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanningProperties;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticShadowResponseMapper;
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
     * Request:  { "question": "...", "tenantId": "..." }
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

        String tenantId = headerTenantId != null && !headerTenantId.isBlank()
                ? headerTenantId
                : stringField(body, "tenantId");
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "tenantId required (header X-Client-Id or body field tenantId)"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = body.containsKey("meta")
                ? (Map<String, Object>) body.get("meta") : Map.of();

        DecisionExecutionContext ctx = DecisionRuntime.contextFrom(tenantId, question, meta);
        log.info("[decision-api] POST /run  runId={}  tenant={}", ctx.runId(), tenantId);

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
