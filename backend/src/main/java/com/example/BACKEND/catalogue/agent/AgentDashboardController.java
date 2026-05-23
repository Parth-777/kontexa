package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.entity.AgentReportEntity;
import com.example.BACKEND.catalogue.entity.InsightCardEntity;
import com.example.BACKEND.catalogue.repository.AgentReportRepository;
import com.example.BACKEND.tenant.TenantContextResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agentic analysis endpoints.
 *
 * POST /api/agent/dashboard
 *   Runs a full analysis for the tenant and returns fresh insight cards.
 *   Cards are also persisted to the database with AWAITING_CONFIRMATION status.
 *
 * GET /api/agent/insights
 *   Returns all active (non-expired) persisted insight cards for the tenant.
 *   This is the "feed" endpoint — the frontend can poll this without triggering
 *   a new LLM analysis.
 *
 * PATCH /api/agent/insights/{id}/status
 *   Updates a card's status to DECLINED or COMPLETED when the user acts on it.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentDashboardController {

    private final AgentOrchestrator     agentOrchestrator;
    private final TenantContextResolver tenantContextResolver;
    private final ReportGenerationAgent reportAgent;
    private final AgentReportRepository reportRepository;

    public AgentDashboardController(
            AgentOrchestrator    agentOrchestrator,
            TenantContextResolver tenantContextResolver,
            ReportGenerationAgent reportAgent,
            AgentReportRepository reportRepository
    ) {
        this.agentOrchestrator  = agentOrchestrator;
        this.tenantContextResolver = tenantContextResolver;
        this.reportAgent        = reportAgent;
        this.reportRepository   = reportRepository;
    }

    /** Triggers a fresh analysis + persists new insight cards. */
    @PostMapping("/dashboard")
    public ResponseEntity<?> runDashboard(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        String clientId = tenantContextResolver.resolveClientId(clientIdHeader, body);
        if (clientId == null || clientId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "clientId is required (X-Client-Id header or body)"));
        }
        try {
            AgentDashboardResult result = agentOrchestrator.analyse(clientId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** Returns persisted active insight cards without triggering a new analysis. */
    @GetMapping("/insights")
    public ResponseEntity<?> getInsights(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(value = "clientId", required = false) String clientIdParam
    ) {
        String clientId = clientIdHeader != null && !clientIdHeader.isBlank()
                ? clientIdHeader : clientIdParam;
        if (clientId == null || clientId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "clientId is required"));
        }
        List<InsightCardEntity> cards = agentOrchestrator.getActiveInsights(clientId);
        return ResponseEntity.ok(cards);
    }

    /**
     * GET /api/agent/insights/followup?clientId=xxx
     * Returns 4-5 suggested follow-up questions derived from the current active insight cards.
     * Called by the frontend whenever the persisted feed is loaded (so suggested questions
     * always appear, regardless of whether a live Refresh was just run).
     */
    @GetMapping("/insights/followup")
    public ResponseEntity<?> getFollowUpQuestions(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(value = "clientId", required = false) String clientIdParam
    ) {
        String clientId = clientIdHeader != null && !clientIdHeader.isBlank()
                ? clientIdHeader : clientIdParam;
        if (clientId == null || clientId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "clientId is required"));

        List<String> questions = agentOrchestrator.generateFollowUpFromActiveInsights(clientId);
        return ResponseEntity.ok(Map.of("followUpQuestions", questions));
    }

    /**
     * GET /api/agent/insights/{id}/evidence
     * Returns the raw data the AI saw when generating this insight card.
     * Used to verify accuracy — "what rows did the AI actually look at?"
     */
    @GetMapping("/insights/{id}/evidence")
    public ResponseEntity<?> getInsightEvidence(
            @PathVariable("id") UUID cardId,
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(value = "clientId", required = false) String clientIdParam
    ) {
        String clientId = clientIdHeader != null && !clientIdHeader.isBlank()
                ? clientIdHeader : clientIdParam;

        return agentOrchestrator.getInsightEvidence(cardId, clientId)
                .map(evidence -> ResponseEntity.ok(Map.of(
                        "cardId",        cardId.toString(),
                        "sourceColumns", evidence.sourceColumns(),
                        "evidence",      evidence.rawEvidence()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Returns saved reports for a tenant (newest first). */
    @GetMapping("/reports")
    public ResponseEntity<?> getReports(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(value = "clientId", required = false) String clientIdParam,
            @RequestParam(value = "type", required = false) String type
    ) {
        String clientId = clientIdHeader != null && !clientIdHeader.isBlank()
                ? clientIdHeader : clientIdParam;
        if (clientId == null || clientId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "clientId is required"));

        List<AgentReportEntity> reports = type != null && !type.isBlank()
                ? reportRepository.findByClientIdAndPeriodTypeOrderByGeneratedAtDesc(clientId, type.toUpperCase())
                : reportRepository.findByClientIdOrderByGeneratedAtDesc(clientId);
        return ResponseEntity.ok(reports);
    }

    /** Generates a report on demand (WEEKLY or MONTHLY). */
    @PostMapping("/reports/generate")
    public ResponseEntity<?> generateReport(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestBody(required = false) Map<String, String> body
    ) {
        String clientId = clientIdHeader != null && !clientIdHeader.isBlank()
                ? clientIdHeader : (body != null ? body.get("clientId") : null);
        if (clientId == null || clientId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "clientId is required"));

        String type = (body != null && body.containsKey("type"))
                ? body.get("type").toUpperCase() : "WEEKLY";
        if (!type.equals("WEEKLY") && !type.equals("MONTHLY"))
            return ResponseEntity.badRequest().body(Map.of("error", "type must be WEEKLY or MONTHLY"));

        try {
            AgentReportEntity report = reportAgent.generateReport(clientId, type);
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Lets the user dismiss (DECLINED) or complete (COMPLETED) a card. */
    @PatchMapping("/insights/{id}/status")
    public ResponseEntity<?> updateInsightStatus(
            @PathVariable("id") UUID cardId,
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestBody Map<String, String> body
    ) {
        String clientId = clientIdHeader != null && !clientIdHeader.isBlank()
                ? clientIdHeader : body.get("clientId");
        String newStatus = body.get("status");

        if (clientId == null || clientId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "clientId is required"));
        }
        if (newStatus == null || (!newStatus.equals("DECLINED") && !newStatus.equals("COMPLETED"))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "status must be COMPLETED (mark as read) or DECLINED (dismiss)"));
        }

        boolean updated = agentOrchestrator.updateInsightStatus(cardId, clientId, newStatus);
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("id", cardId.toString(), "status", newStatus));
    }

    /**
     * DELETE /api/agent/insights/pending?clientId=xxx
     * Manually retire all AWAITING_CONFIRMATION cards (clears accumulated stale cards).
     * Safe to call at any time — DECLINED/COMPLETED cards are kept.
     */
    @DeleteMapping("/insights/pending")
    public ResponseEntity<?> clearPendingInsights(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(value = "clientId", required = false) String clientIdParam
    ) {
        String clientId = (clientIdHeader != null && !clientIdHeader.isBlank())
                ? clientIdHeader : clientIdParam;
        if (clientId == null || clientId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "clientId is required"));

        int cleared = agentOrchestrator.clearPendingInsights(clientId);
        return ResponseEntity.ok(Map.of("cleared", cleared, "clientId", clientId));
    }
}
