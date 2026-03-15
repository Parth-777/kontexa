package com.example.BACKEND.catalogue.query;

import com.example.BACKEND.tenant.TenantContextResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/query")
public class CatalogueQueryController {

    private final CatalogueQueryService queryService;
    private final TenantContextResolver tenantContextResolver;

    public CatalogueQueryController(
            CatalogueQueryService queryService,
            TenantContextResolver tenantContextResolver
    ) {
        this.queryService = queryService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping("/ask")
    public ResponseEntity<?> ask(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestBody Map<String, Object> body
    ) {
        String question = body.get("question") == null ? null : String.valueOf(body.get("question")).trim();
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        String clientId = tenantContextResolver.resolveClientId(clientIdHeader, body);
        CatalogueQueryService.QueryResult result = queryService.ask(clientId, question);
        return ResponseEntity.ok(Map.of(
                "question", result.getQuestion(),
                "generatedSql", result.getGeneratedSql(),
                "rowCount", result.getRowCount(),
                "rows", result.getRows()
        ));
    }
}
