package com.example.BACKEND.catalogue.query;

import com.example.BACKEND.tenant.TenantContextResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * POST /api/query/ask — reasoning-first chatbot.
 */
@RestController
@RequestMapping("/api/query")
public class CatalogueQueryController {

    private final ChatOrchestratorService chatOrchestrator;
    private final TenantContextResolver tenantContextResolver;

    public CatalogueQueryController(
            ChatOrchestratorService chatOrchestrator,
            TenantContextResolver tenantContextResolver
    ) {
        this.chatOrchestrator = chatOrchestrator;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping("/ask")
    public ResponseEntity<?> ask(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestBody Map<String, Object> body
    ) {
        String question = body.get("question") == null
                ? null : String.valueOf(body.get("question")).trim();
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        String clientId = tenantContextResolver.resolveClientId(clientIdHeader, body);
        ChatOrchestratorService.RouteType route = chatOrchestrator.classifyRoute(question, clientId);
        List<ChatOrchestratorService.ChatTurn> history = parseHistory(body.get("history"));

        ChatOrchestratorService.ChatResponse result = chatOrchestrator.handle(question, clientId, history);

        Map<String, Object> response = new HashMap<>();
        response.put("type", result.type());
        response.put("route", route.name().toLowerCase());
        response.put("answer", result.answer());
        response.put("generatedSql", result.sql());
        response.put("rowCount", result.rowCount());
        response.put("rows", result.rows() != null ? result.rows() : java.util.List.of());
        response.put("followUpSuggestions", result.followUpSuggestions());
        return ResponseEntity.ok(response);
    }

    @SuppressWarnings("unchecked")
    private List<ChatOrchestratorService.ChatTurn> parseHistory(Object rawHistory) {
        if (!(rawHistory instanceof List<?> list) || list.isEmpty()) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(m -> new ChatOrchestratorService.ChatTurn(
                        String.valueOf(m.getOrDefault("role", "")).trim(),
                        String.valueOf(m.getOrDefault("text", "")).trim()))
                .filter(t -> !t.text().isBlank())
                .limit(8)
                .toList();
    }
}
