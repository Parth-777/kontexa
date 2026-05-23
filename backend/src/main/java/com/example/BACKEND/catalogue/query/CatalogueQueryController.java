package com.example.BACKEND.catalogue.query;

import com.example.BACKEND.tenant.TenantContextResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * POST /api/query/ask — general data chatbot.
 *
 * Every question is handled by OpenAI with full schema access. The model can
 * run multiple SQL queries against the tenant's database, review results, and
 * produce a final answer. Not tied to insight cards.
 */
@RestController
@RequestMapping("/api/query")
public class CatalogueQueryController {

    private final ChatOrchestratorService chatOrchestrator;
    private final TenantContextResolver   tenantContextResolver;

    public CatalogueQueryController(
            ChatOrchestratorService chatOrchestrator,
            TenantContextResolver tenantContextResolver
    ) {
        this.chatOrchestrator      = chatOrchestrator;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping("/ask")
    public ResponseEntity<?> ask(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestBody Map<String, Object> body
    ) {
        String question = body.get("question") == null
                ? null : String.valueOf(body.get("question")).trim();
        if (question == null || question.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));

        String clientId = tenantContextResolver.resolveClientId(clientIdHeader, body);

        ChatOrchestratorService.ChatResponse result = chatOrchestrator.handle(question, clientId);

        Map<String, Object> response = new HashMap<>();
        response.put("type",                result.type());
        response.put("answer",              result.answer());
        response.put("generatedSql",        result.sql());
        response.put("rowCount",            result.rowCount());
        response.put("rows",                result.rows() != null ? result.rows() : java.util.List.of());
        response.put("followUpSuggestions", result.followUpSuggestions());
        return ResponseEntity.ok(response);
    }
}
