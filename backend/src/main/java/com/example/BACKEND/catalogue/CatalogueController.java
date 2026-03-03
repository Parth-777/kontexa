package com.example.BACKEND.catalogue;

import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.example.BACKEND.catalogue.service.CatalogueEnrichmentService;
import com.example.BACKEND.catalogue.service.CatalogueStorageService;
import com.example.BACKEND.catalogue.service.DataSamplerService;
import com.example.BACKEND.catalogue.service.SchemaDiscoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CatalogueController
 *
 * REST API for the full catalogue creation pipeline.
 *
 * STAGE ENDPOINTS (run in order for a new client):
 *
 *   POST /api/catalogue/discover
 *     → Stage 1 only: tables + columns structure (fast, no data read)
 *
 *   POST /api/catalogue/build
 *     → Stage 1 + 2: structure + sampled values (no LLM)
 *
 *   POST /api/catalogue/build-full
 *     → Stage 1 + 2 + 3: structure + sampling + LLM enrichment
 *     → This is the main endpoint for onboarding a new client
 *     → Takes ~30-60 seconds depending on table count
 *
 * APPROVAL ENDPOINTS (after reviewing the catalogue):
 *
 *   POST /api/catalogue/save?clientId=acme
 *     → Persist the enriched catalogue as DRAFT in the database
 *     → Body: the CatalogueResult JSON returned by /build-full
 *
 *   POST /api/catalogue/{id}/approve
 *     → Mark a saved catalogue as APPROVED (ready for NLP queries)
 *
 *   POST /api/catalogue/{id}/reject
 *     → Mark a saved catalogue as REJECTED (needs a re-run)
 *
 *   GET  /api/catalogue/list?clientId=acme
 *     → List all catalogues for a client
 */
@RestController
@RequestMapping("/api/catalogue")
public class CatalogueController {

    private final SchemaDiscoveryService schemaDiscovery;
    private final DataSamplerService dataSampler;
    private final CatalogueEnrichmentService enrichmentService;
    private final CatalogueStorageService storageService;
    private final CatalogueApprovalService approvalService;

    public CatalogueController(SchemaDiscoveryService schemaDiscovery,
                                DataSamplerService dataSampler,
                                CatalogueEnrichmentService enrichmentService,
                                CatalogueStorageService storageService,
                                CatalogueApprovalService approvalService) {
        this.schemaDiscovery = schemaDiscovery;
        this.dataSampler = dataSampler;
        this.enrichmentService = enrichmentService;
        this.storageService = storageService;
        this.approvalService = approvalService;
    }

    /**
     * Stage 1 only: discover tables and columns.
     * Fast — useful to verify schema before running the full pipeline.
     */
    @PostMapping("/discover")
    public ResponseEntity<CatalogueResult> discover(@RequestBody Map<String, String> body) {
        String schema = body.getOrDefault("schema", "public");
        System.out.println("[Catalogue] Starting schema discovery for schema: " + schema);

        CatalogueResult result = schemaDiscovery.discover(schema);

        System.out.println("[Catalogue] Discovery complete: "
                + result.getTotalTables() + " tables, "
                + result.getTotalColumns() + " columns");

        return ResponseEntity.ok(result);
    }

    /**
     * Stage 1 + 2: discover + sample.
     * Returns raw schema with real column values. No LLM call.
     */
    @PostMapping("/build")
    public ResponseEntity<CatalogueResult> build(@RequestBody Map<String, String> body) {
        String schema = body.getOrDefault("schema", "public");
        System.out.println("[Catalogue] Starting catalogue build (no enrichment) for schema: " + schema);

        CatalogueResult result = schemaDiscovery.discover(schema);
        System.out.println("[Catalogue] Stage 1 complete — " + result.getTotalTables() + " tables");

        dataSampler.sample(result);
        System.out.println("[Catalogue] Stage 2 complete — sampling done");

        return ResponseEntity.ok(result);
    }

    /**
     * Stage 1 + 2 + 3: discover + sample + LLM enrichment.
     *
     * This is the MAIN endpoint for creating a client catalogue.
     * Run this once per client during onboarding.
     *
     * Takes ~30-60 seconds for a typical database.
     * Returns a fully enriched catalogue ready for client review and approval.
     */
    @PostMapping("/build-full")
    public ResponseEntity<CatalogueResult> buildFull(@RequestBody Map<String, String> body) {
        String schema = body.getOrDefault("schema", "public");
        System.out.println("[Catalogue] Starting FULL catalogue build for schema: " + schema);

        // Stage 1: discover structure
        CatalogueResult result = schemaDiscovery.discover(schema);
        System.out.println("[Catalogue] Stage 1 complete — "
                + result.getTotalTables() + " tables, "
                + result.getTotalColumns() + " columns");

        // Stage 2: sample real values
        dataSampler.sample(result);
        System.out.println("[Catalogue] Stage 2 complete — sampling done");

        // Stage 3: LLM enrichment (descriptions + synonyms + value meanings + roles)
        enrichmentService.enrich(result);
        System.out.println("[Catalogue] Stage 3 complete — LLM enrichment done");

        result.setStatus("DRAFT");
        System.out.println("[Catalogue] Full catalogue ready for client review. Status: DRAFT");

        return ResponseEntity.ok(result);
    }

    // ── Approval Flow ────────────────────────────────────────────────────────

    /**
     * Persist an enriched CatalogueResult to the database as DRAFT.
     *
     * Call this after /build-full and reviewing the JSON output.
     * Body: the CatalogueResult JSON returned by /build-full
     * Query param: clientId (e.g. "netflix", "acme")
     *
     * Returns the saved catalogue's id — keep it, you'll need it to approve.
     */
    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> save(
            @RequestParam String clientId,
            @RequestBody CatalogueResult result) {

        System.out.println("[Catalogue] Saving catalogue for client: " + clientId);
        ClientCatalogueEntity saved = storageService.save(clientId, result);
        System.out.println("[Catalogue] Saved with id=" + saved.getId() + " status=" + saved.getStatus());

        return ResponseEntity.ok(Map.of(
                "id",       saved.getId(),
                "clientId", saved.getClientId(),
                "status",   saved.getStatus(),
                "tables",   saved.getTables().size()
        ));
    }

    /**
     * Approve a saved catalogue — makes it available for NLP queries.
     *
     * POST /api/catalogue/{id}/approve
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable Long id) {
        ClientCatalogueEntity approved = approvalService.approve(id);
        System.out.println("[Catalogue] Approved catalogue id=" + id);
        return ResponseEntity.ok(Map.of(
                "id",     approved.getId(),
                "status", approved.getStatus(),
                "client", approved.getClientId()
        ));
    }

    /**
     * Reject a saved catalogue (needs rework / re-run).
     *
     * POST /api/catalogue/{id}/reject
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable Long id) {
        ClientCatalogueEntity rejected = approvalService.reject(id);
        return ResponseEntity.ok(Map.of(
                "id",     rejected.getId(),
                "status", rejected.getStatus()
        ));
    }

    /**
     * List all catalogues for a client (DRAFT, APPROVED, REJECTED).
     *
     * GET /api/catalogue/list?clientId=netflix
     */
    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam String clientId) {
        List<Map<String, Object>> response = approvalService.listForClient(clientId)
                .stream()
                .map(c -> Map.<String, Object>of(
                        "id",          c.getId(),
                        "clientId",    c.getClientId(),
                        "schemaName",  c.getSchemaName(),
                        "status",      c.getStatus(),
                        "tableCount",  c.getTables().size(),
                        "createdAt",   c.getCreatedAt().toString()
                ))
                .toList();
        return ResponseEntity.ok(response);
    }
}
