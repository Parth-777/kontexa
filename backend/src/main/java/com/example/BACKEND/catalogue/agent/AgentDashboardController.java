package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.entity.AgentReportEntity;
import com.example.BACKEND.catalogue.entity.InsightCardEntity;
import com.example.BACKEND.catalogue.charts.DynamicChartResponse;
import com.example.BACKEND.catalogue.charts.DynamicChartService;
import com.example.BACKEND.catalogue.repository.AgentReportRepository;
import com.example.BACKEND.identity.auth.AuthorizationService;
import com.example.BACKEND.tenant.TenantContextResolver;
import com.example.BACKEND.tenancy.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agentic analysis endpoints.
 *
 * Tenant ownership comes exclusively from the authenticated {@code TenantContext}.
 * Client {@code X-Client-Id}/{@code clientId} hints are mismatch-checked only.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentDashboardController {

    private final AgentOrchestrator     agentOrchestrator;
    private final TenantContextResolver tenantContextResolver;
    private final ReportGenerationAgent reportAgent;
    private final AgentReportRepository reportRepository;
    private final DynamicChartService   dynamicChartService;
    private final AuthorizationService  authorization;

    public AgentDashboardController(
            AgentOrchestrator    agentOrchestrator,
            TenantContextResolver tenantContextResolver,
            ReportGenerationAgent reportAgent,
            AgentReportRepository reportRepository,
            DynamicChartService dynamicChartService,
            AuthorizationService authorization
    ) {
        this.agentOrchestrator  = agentOrchestrator;
        this.tenantContextResolver = tenantContextResolver;
        this.reportAgent        = reportAgent;
        this.reportRepository   = reportRepository;
        this.dynamicChartService = dynamicChartService;
        this.authorization = authorization;
    }

    /** Triggers a fresh analysis + persists new insight cards. */
    @PostMapping("/dashboard")
    public ResponseEntity<?> runDashboard(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        authorization.requireCanQuery();
        String clientId = tenantContextResolver.resolveClientId(clientIdHeader, body);
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
        String clientId = tenantContextResolver.resolveClientId(clientIdHeader, clientIdParam);
        List<InsightCardEntity> cards = agentOrchestrator.getActiveInsights(clientId);
        return ResponseEntity.ok(cards);
    }

    /**
     * GET /api/agent/charts
     * Returns chart specs for the sidebar panel beside the Agent Feed.
     */
    @GetMapping("/charts")
    public ResponseEntity<?> getCharts(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(value = "clientId", required = false) String clientIdParam
    ) {
        String clientId = tenantContextResolver.resolveClientId(clientIdHeader, clientIdParam);
        return ResponseEntity.ok(agentOrchestrator.getChartPanel(clientId));
    }

    /**
     * POST /api/agent/charts/generate
     * Natural-language chart builder.
     */
    @PostMapping("/charts/generate")
    public ResponseEntity<?> generateChart(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestBody Map<String, Object> body
    ) {
        authorization.requireCanQuery();
        String clientId = tenantContextResolver.resolveClientId(clientIdHeader, body);
        String request = body.get("request") == null ? null : String.valueOf(body.get("request")).trim();
        if (request == null || request.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "request is required"));
        }
        try {
            return ResponseEntity.ok(
                    DynamicChartResponse.from(dynamicChartService.generate(request, clientId)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/agent/insights/followup
     * Suggested follow-up questions from active insight cards.
     */
    @GetMapping("/insights/followup")
    public ResponseEntity<?> getFollowUpQuestions(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(value = "clientId", required = false) String clientIdParam
    ) {
        String clientId = tenantContextResolver.resolveClientId(clientIdHeader, clientIdParam);
        List<String> questions = agentOrchestrator.generateFollowUpFromActiveInsights(clientId);
        return ResponseEntity.ok(Map.of("followUpQuestions", questions));
    }

    /**
     * GET /api/agent/insights/{id}/evidence
     * Raw evidence for an insight card — ownership required.
     */
    @GetMapping("/insights/{id}/evidence")
    public ResponseEntity<?> getInsightEvidence(
            @PathVariable("id") UUID cardId,
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(value = "clientId", required = false) String clientIdParam
    ) {
        String clientId = tenantContextResolver.resolveClientId(clientIdHeader, clientIdParam);
        return agentOrchestrator.getInsightEvidence(cardId, clientId)
                .map(evidence -> ResponseEntity.ok(Map.of(
                        "cardId",        cardId.toString(),
                        "sourceColumns", evidence.sourceColumns(),
                        "evidence",      evidence.rawEvidence()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Returns saved reports for the authenticated tenant (newest first). */
    @GetMapping("/reports")
    public ResponseEntity<?> getReports(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(value = "clientId", required = false) String clientIdParam,
            @RequestParam(value = "type", required = false) String type
    ) {
        TenantContext ctx = tenantContextResolver.resolveContext(clientIdHeader, clientIdParam);
        List<AgentReportEntity> reports = type != null && !type.isBlank()
                ? reportRepository.findByTenantIdAndPeriodTypeOrderByGeneratedAtDesc(
                        ctx.tenantId(), type.toUpperCase())
                : reportRepository.findByTenantIdOrderByGeneratedAtDesc(ctx.tenantId());
        if (reports.isEmpty()) {
            reports = type != null && !type.isBlank()
                    ? reportRepository.findByClientIdAndPeriodTypeOrderByGeneratedAtDesc(
                            ctx.legacyTenantKey(), type.toUpperCase())
                    : reportRepository.findByClientIdOrderByGeneratedAtDesc(ctx.legacyTenantKey());
        }
        return ResponseEntity.ok(reports);
    }

    /** Generates a report on demand (WEEKLY or MONTHLY). */
    @PostMapping("/reports/generate")
    public ResponseEntity<?> generateReport(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestBody(required = false) Map<String, String> body
    ) {
        authorization.requireCanQuery();
        String clientId = tenantContextResolver.resolveClientId(
                clientIdHeader, body != null ? body : Map.of());
        String type = (body != null && body.containsKey("type") && body.get("type") != null)
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
        authorization.requireCanQuery();
        String clientId = tenantContextResolver.resolveClientId(clientIdHeader, body);
        String newStatus = body.get("status");

        if (newStatus == null || (!newStatus.equals("DECLINED")
                && !newStatus.equals("COMPLETED")
                && !newStatus.equals("AWAITING_CONFIRMATION"))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error",
                            "status must be COMPLETED (read), AWAITING_CONFIRMATION (unread), or DECLINED (dismiss)"));
        }

        String dismissReason = body.get("dismissReason");
        boolean updated = agentOrchestrator.updateInsightStatus(
                cardId, clientId, newStatus, dismissReason);
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("id", cardId.toString(), "status", newStatus));
    }

    /**
     * DELETE /api/agent/insights/pending
     * Retire all AWAITING_CONFIRMATION cards for the authenticated tenant.
     */
    @DeleteMapping("/insights/pending")
    public ResponseEntity<?> clearPendingInsights(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(value = "clientId", required = false) String clientIdParam
    ) {
        authorization.requireCanQuery();
        String clientId = tenantContextResolver.resolveClientId(clientIdHeader, clientIdParam);
        int cleared = agentOrchestrator.clearPendingInsights(clientId);
        return ResponseEntity.ok(Map.of("cleared", cleared, "clientId", clientId));
    }
}
