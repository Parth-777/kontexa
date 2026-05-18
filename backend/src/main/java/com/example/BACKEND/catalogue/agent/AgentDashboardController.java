package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.tenant.TenantContextResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoint: POST /api/agent/dashboard
 *
 * Triggers the full agentic analysis for a tenant and returns
 * an AgentDashboardResult containing KPI cards, insights,
 * anomalies, investigations, and follow-up questions.
 *
 * Uses the same X-Client-Id header pattern as /api/query/ask.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentDashboardController {

    private final AgentAnalysisService   agentAnalysisService;
    private final TenantContextResolver  tenantContextResolver;

    public AgentDashboardController(
            AgentAnalysisService  agentAnalysisService,
            TenantContextResolver tenantContextResolver
    ) {
        this.agentAnalysisService  = agentAnalysisService;
        this.tenantContextResolver = tenantContextResolver;
    }

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
            AgentDashboardResult result = agentAnalysisService.analyse(clientId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
