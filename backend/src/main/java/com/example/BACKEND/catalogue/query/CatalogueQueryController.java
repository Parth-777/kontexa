package com.example.BACKEND.catalogue.query;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * CatalogueQueryController
 *
 * Generic NLP query endpoint — works for ANY client with an approved catalogue.
 *
 * POST /api/query/ask
 * {
 *   "clientId": "netflix",
 *   "question": "How many movies were added in 2023?"
 * }
 *
 * Returns:
 * {
 *   "question":     "How many movies were added in 2023?",
 *   "generatedSql": "SELECT COUNT(*) ...",
 *   "rowCount":     1,
 *   "rows":         [{ "count": 512 }]
 * }
 */
@RestController
@RequestMapping("/api/query")
public class CatalogueQueryController {

    private final CatalogueQueryService queryService;

    public CatalogueQueryController(CatalogueQueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody Map<String, String> body) {

        String clientId = body.get("clientId");
        String question = body.get("question");

        if (clientId == null || clientId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "clientId is required"));
        }
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "question is required"));
        }

        System.out.println("[QueryController] clientId=" + clientId + " | question=" + question);

        try {
            CatalogueQueryService.QueryResult result = queryService.ask(clientId, question);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            // No approved catalogue found
            return ResponseEntity.status(404)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            System.err.println("[QueryController] Error: " + e.getMessage());
            return ResponseEntity.status(500)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
