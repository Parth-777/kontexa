package com.example.BACKEND.catalogue;

import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.example.BACKEND.catalogue.entity.CatalogueTableEntity;
import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.example.BACKEND.catalogue.service.CatalogueEnrichmentService;
import com.example.BACKEND.catalogue.service.CatalogueStorageService;
import com.example.BACKEND.catalogue.service.DataSamplerService;
import com.example.BACKEND.catalogue.service.SchemaDiscoveryService;
import com.example.BACKEND.catalogue.service.BigQueryCatalogueService;
import com.example.BACKEND.catalogue.service.SnowflakeCatalogueService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
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
    private final TenantCloudConnectionService cloudConnectionService;
    private final BigQueryCatalogueService bigQueryCatalogueService;
    private final SnowflakeCatalogueService snowflakeCatalogueService;

    public CatalogueController(SchemaDiscoveryService schemaDiscovery,
                                DataSamplerService dataSampler,
                                CatalogueEnrichmentService enrichmentService,
                                CatalogueStorageService storageService,
                                CatalogueApprovalService approvalService,
                                TenantCloudConnectionService cloudConnectionService,
                                BigQueryCatalogueService bigQueryCatalogueService,
                                SnowflakeCatalogueService snowflakeCatalogueService) {
        this.schemaDiscovery = schemaDiscovery;
        this.dataSampler = dataSampler;
        this.enrichmentService = enrichmentService;
        this.storageService = storageService;
        this.approvalService = approvalService;
        this.cloudConnectionService = cloudConnectionService;
        this.bigQueryCatalogueService = bigQueryCatalogueService;
        this.snowflakeCatalogueService = snowflakeCatalogueService;
    }

    /**
     * Stage 1 only: discover tables and columns.
     * Fast — useful to verify schema before running the full pipeline.
     */
    @PostMapping("/discover")
    public ResponseEntity<CatalogueResult> discover(@RequestBody Map<String, String> body) {
        String schema = body.getOrDefault("schema", "public");
        System.out.println("[Catalogue] Starting schema discovery for schema: " + schema);
        CatalogueResult result = resolveCloud(body)
                .map(cc -> discoverFromCloud(cc, false))
                .orElseGet(() -> schemaDiscovery.discover(schema));

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
        CatalogueResult result;
        var cloud = resolveCloud(body);
        if (cloud.isPresent()) {
            result = discoverFromCloud(cloud.get(), true);
            System.out.println("[Catalogue] Stage 1+2 complete via " + cloud.get().provider() + " connector");
        } else {
            result = schemaDiscovery.discover(schema);
            System.out.println("[Catalogue] Stage 1 complete — " + result.getTotalTables() + " tables");
            dataSampler.sample(result);
            System.out.println("[Catalogue] Stage 2 complete — sampling done");
        }
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

        CatalogueResult result;
        var cloud = resolveCloud(body);
        if (cloud.isPresent()) {
            result = discoverFromCloud(cloud.get(), true);
            System.out.println("[Catalogue] Stage 1+2 complete via " + cloud.get().provider() + " connector — "
                    + result.getTotalTables() + " tables, "
                    + result.getTotalColumns() + " columns");
        } else {
            result = schemaDiscovery.discover(schema);
            System.out.println("[Catalogue] Stage 1 complete — "
                    + result.getTotalTables() + " tables, "
                    + result.getTotalColumns() + " columns");
            dataSampler.sample(result);
            System.out.println("[Catalogue] Stage 2 complete — sampling done");
        }

        enrichmentService.enrich(result);
        System.out.println("[Catalogue] Stage 3 complete — LLM enrichment done");
        result.setStatus("DRAFT");
        System.out.println("[Catalogue] Full catalogue ready for client review. Status: DRAFT");

        return ResponseEntity.ok(result);
    }

    // ── Cloud routing helpers ─────────────────────────────────────────────────

    /** Opaque wrapper so discover/build endpoints don't need to know the provider. */
    private record CloudConnectorConfig(
            String provider,
            TenantCloudConnectionService.BigQueryConfig bigQuery,
            TenantCloudConnectionService.SnowflakeConfig snowflake
    ) {}

    private java.util.Optional<CloudConnectorConfig> resolveCloud(Map<String, String> body) {
        for (String key : List.of(
                body.getOrDefault("tenantId", ""),
                body.getOrDefault("clientId", ""),
                body.getOrDefault("schema", "")
        )) {
            if (key.isBlank()) continue;
            String provider = cloudConnectionService.getProvider(key);
            if ("bigquery".equals(provider)) {
                return cloudConnectionService.getBigQueryConfig(key)
                        .map(cfg -> new CloudConnectorConfig("bigquery", cfg, null));
            }
            if ("snowflake".equals(provider)) {
                return cloudConnectionService.getSnowflakeConfig(key)
                        .map(cfg -> new CloudConnectorConfig("snowflake", null, cfg));
            }
        }
        return java.util.Optional.empty();
    }

    private CatalogueResult discoverFromCloud(CloudConnectorConfig cc, boolean withSampling) {
        return switch (cc.provider()) {
            case "bigquery"  -> bigQueryCatalogueService.discover(cc.bigQuery(),   withSampling);
            case "snowflake" -> snowflakeCatalogueService.discover(cc.snowflake(), withSampling);
            default -> throw new IllegalStateException("Unknown cloud provider: " + cc.provider());
        };
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
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable Long id,
            @RequestParam(required = false) String clientId) {
        ClientCatalogueEntity approved = approvalService.approve(id, clientId);
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
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable Long id,
            @RequestParam(required = false) String clientId) {
        ClientCatalogueEntity rejected = approvalService.reject(id, clientId);
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
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String tenantSchema) {
        List<ClientCatalogueEntity> catalogues;
        if (clientId != null && !clientId.isBlank()) {
            catalogues = approvalService.listForClient(clientId);
        } else {
            catalogues = approvalService.listForTenantContext(tenantId, tenantSchema);
        }

        List<Map<String, Object>> response = catalogues
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

    /**
     * Get a single catalogue in preview form so tenant can review before approving.
     *
     * GET /api/catalogue/{id}?clientId=netflix
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(
            @PathVariable Long id,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String tenantSchema) {
        ClientCatalogueEntity c = approvalService.getForTenantContext(id, tenantId, tenantSchema, clientId);
        long totalColumns = c.getTables().stream().mapToLong(t -> t.getColumns().size()).sum();
        List<Map<String, Object>> tables = c.getTables().stream()
                .map(this::toTablePreview)
                .toList();

        return ResponseEntity.ok(Map.of(
                "id", c.getId(),
                "clientId", c.getClientId(),
                "schemaName", c.getSchemaName(),
                "databaseName", c.getDatabaseName() == null ? "" : c.getDatabaseName(),
                "status", c.getStatus(),
                "tableCount", c.getTables().size(),
                "columnCount", totalColumns,
                "createdAt", c.getCreatedAt().toString(),
                "updatedAt", c.getUpdatedAt().toString(),
                "tables", tables
        ));
    }

    /**
     * Update catalogue content (table/column descriptions and roles) from tenant review page.
     *
     * PUT /api/catalogue/{id}?tenantId=...&tenantSchema=...
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateById(
            @PathVariable Long id,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String tenantSchema,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = body.get("tables") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();

        ClientCatalogueEntity updated = approvalService.updateFromTenantContext(
                id, tenantId, tenantSchema, clientId, tables
        );
        return ResponseEntity.ok(Map.of(
                "id", updated.getId(),
                "status", updated.getStatus(),
                "updatedAt", updated.getUpdatedAt().toString()
        ));
    }

    private Map<String, Object> toTablePreview(CatalogueTableEntity table) {
        List<Map<String, Object>> columns = table.getColumns()
                .stream()
                .map(col -> Map.<String, Object>of(
                        "columnName", col.getColumnName(),
                        "dataType", col.getDataType() == null ? "" : col.getDataType(),
                        "description", col.getDescription() == null ? "" : col.getDescription(),
                        "role", col.getRole() == null ? "" : col.getRole(),
                        "sampleValues", col.getSampleValues() == null ? "[]" : col.getSampleValues()
                ))
                .toList();
        return Map.of(
                "tableName", table.getTableName(),
                "tableSchema", table.getTableSchema(),
                "description", table.getDescription() == null ? "" : table.getDescription(),
                "rowCount", table.getRowCount() == null ? 0L : table.getRowCount(),
                "columnCount", table.getColumns().size(),
                "columns", columns
        );
    }
}
