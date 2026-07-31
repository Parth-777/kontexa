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
import com.example.BACKEND.tenant.TenantContextResolver;
import com.example.BACKEND.tenancy.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Catalogue creation and approval API.
 *
 * Tenant ownership comes exclusively from the authenticated {@code TenantContext}.
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
    private final TenantContextResolver tenantContextResolver;

    public CatalogueController(SchemaDiscoveryService schemaDiscovery,
                                DataSamplerService dataSampler,
                                CatalogueEnrichmentService enrichmentService,
                                CatalogueStorageService storageService,
                                CatalogueApprovalService approvalService,
                                TenantCloudConnectionService cloudConnectionService,
                                BigQueryCatalogueService bigQueryCatalogueService,
                                SnowflakeCatalogueService snowflakeCatalogueService,
                                TenantContextResolver tenantContextResolver) {
        this.schemaDiscovery = schemaDiscovery;
        this.dataSampler = dataSampler;
        this.enrichmentService = enrichmentService;
        this.storageService = storageService;
        this.approvalService = approvalService;
        this.cloudConnectionService = cloudConnectionService;
        this.bigQueryCatalogueService = bigQueryCatalogueService;
        this.snowflakeCatalogueService = snowflakeCatalogueService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping("/discover")
    public ResponseEntity<CatalogueResult> discover(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestBody Map<String, String> body) {
        String tenantKey = tenantContextResolver.resolveClientId(clientIdHeader, body);
        // Local PG discovery is pinned to the authenticated tenant key.
        // Body "schema" is never used as ownership authority.
        String schema = tenantKey;
        System.out.println("[Catalogue] Starting schema discovery for tenant=" + tenantKey);
        CatalogueResult result = resolveCloud(tenantKey)
                .map(cc -> discoverFromCloud(cc, false))
                .orElseGet(() -> schemaDiscovery.discover(schema));

        System.out.println("[Catalogue] Discovery complete: "
                + result.getTotalTables() + " tables, "
                + result.getTotalColumns() + " columns");

        return ResponseEntity.ok(result);
    }

    @PostMapping("/build")
    public ResponseEntity<CatalogueResult> build(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestBody Map<String, String> body) {
        String tenantKey = tenantContextResolver.resolveClientId(clientIdHeader, body);
        String schema = tenantKey;
        System.out.println("[Catalogue] Starting catalogue build for tenant=" + tenantKey);
        CatalogueResult result;
        var cloud = resolveCloud(tenantKey);
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

    @PostMapping("/build-full")
    public ResponseEntity<CatalogueResult> buildFull(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestBody Map<String, String> body) {
        String tenantKey = tenantContextResolver.resolveClientId(clientIdHeader, body);
        String schema = tenantKey;
        System.out.println("[Catalogue] Starting FULL catalogue build for tenant=" + tenantKey);

        CatalogueResult result;
        var cloud = resolveCloud(tenantKey);
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

    private record CloudConnectorConfig(
            String provider,
            TenantCloudConnectionService.BigQueryConfig bigQuery,
            TenantCloudConnectionService.SnowflakeConfig snowflake
    ) {}

    private java.util.Optional<CloudConnectorConfig> resolveCloud(String tenantKey) {
        if (tenantKey == null || tenantKey.isBlank()) {
            return java.util.Optional.empty();
        }
        String provider = cloudConnectionService.getProvider(tenantKey);
        if ("bigquery".equals(provider)) {
            return cloudConnectionService.getBigQueryConfig(tenantKey)
                    .map(cfg -> new CloudConnectorConfig("bigquery", cfg, null));
        }
        if ("snowflake".equals(provider)) {
            return cloudConnectionService.getSnowflakeConfig(tenantKey)
                    .map(cfg -> new CloudConnectorConfig("snowflake", null, cfg));
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

    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> save(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(required = false) String clientId,
            @RequestBody CatalogueResult result) {

        TenantContext ctx = tenantContextResolver.resolveContext(clientIdHeader, clientId);
        System.out.println("[Catalogue] Saving catalogue for tenant=" + ctx.tenantId()
                + " slug=" + ctx.legacyTenantKey());
        ClientCatalogueEntity saved = storageService.save(
                ctx.legacyTenantKey(), ctx.tenantId(), result);
        System.out.println("[Catalogue] Saved with id=" + saved.getId() + " status=" + saved.getStatus());

        return ResponseEntity.ok(Map.of(
                "id",       saved.getId(),
                "clientId", saved.getClientId(),
                "tenantId", saved.getTenantId() != null ? saved.getTenantId().toString() : "",
                "status",   saved.getStatus(),
                "tables",   saved.getTables().size()
        ));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable Long id,
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(required = false) String clientId) {
        TenantContext ctx = tenantContextResolver.resolveContext(clientIdHeader, clientId);
        ClientCatalogueEntity approved = approvalService.approve(id, ctx.legacyTenantKey());
        System.out.println("[Catalogue] Approved catalogue id=" + id);
        return ResponseEntity.ok(Map.of(
                "id",     approved.getId(),
                "status", approved.getStatus(),
                "client", approved.getClientId()
        ));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable Long id,
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(required = false) String clientId) {
        TenantContext ctx = tenantContextResolver.resolveContext(clientIdHeader, clientId);
        ClientCatalogueEntity rejected = approvalService.reject(id, ctx.legacyTenantKey());
        return ResponseEntity.ok(Map.of(
                "id",     rejected.getId(),
                "status", rejected.getStatus()
        ));
    }

    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String tenantSchema) {
        TenantContext ctx = tenantContextResolver.resolveContext(
                clientIdHeader, clientId, tenantId, tenantSchema);
        List<ClientCatalogueEntity> catalogues = approvalService.listForTenant(ctx);

        List<Map<String, Object>> response = catalogues
                .stream()
                .map(c -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("clientId", c.getClientId());
                    m.put("tenantId", c.getTenantId() != null ? c.getTenantId().toString() : "");
                    m.put("schemaName", c.getSchemaName());
                    m.put("status", c.getStatus());
                    m.put("tableCount", c.getTables().size());
                    m.put("createdAt", c.getCreatedAt().toString());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(
            @PathVariable Long id,
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String tenantSchema) {
        TenantContext ctx = tenantContextResolver.resolveContext(
                clientIdHeader, clientId, tenantId, tenantSchema);
        ClientCatalogueEntity c = approvalService.requireOwned(id, ctx);
        long totalColumns = c.getTables().stream().mapToLong(t -> t.getColumns().size()).sum();
        List<Map<String, Object>> tables = c.getTables().stream()
                .map(this::toTablePreview)
                .toList();

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("id", c.getId());
        response.put("clientId", c.getClientId());
        response.put("tenantId", c.getTenantId() != null ? c.getTenantId().toString() : "");
        response.put("schemaName", c.getSchemaName());
        response.put("databaseName", c.getDatabaseName() == null ? "" : c.getDatabaseName());
        response.put("status", c.getStatus());
        response.put("tableCount", c.getTables().size());
        response.put("columnCount", totalColumns);
        response.put("createdAt", c.getCreatedAt().toString());
        response.put("updatedAt", c.getUpdatedAt().toString());
        response.put("businessProfile", c.getBusinessProfile() == null ? "" : c.getBusinessProfile());
        response.put("globalCaveats", c.getGlobalCaveats() == null ? "[]" : c.getGlobalCaveats());
        response.put("entities", c.getEntitiesJson() == null ? "[]" : c.getEntitiesJson());
        if (c.getLineageKey() != null) {
            response.put("lineageKey", c.getLineageKey());
        }
        if (c.getSupersedesCatalogueId() != null) {
            response.put("supersedesCatalogueId", c.getSupersedesCatalogueId());
        }
        response.put("tables", tables);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateById(
            @PathVariable Long id,
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String tenantSchema,
            @RequestBody Map<String, Object> body) {
        TenantContext ctx = tenantContextResolver.resolveContext(
                clientIdHeader, clientId, tenantId, tenantSchema);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = body.get("tables") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();

        ClientCatalogueEntity updated = approvalService.updateOwned(id, ctx, tables);
        return ResponseEntity.ok(Map.of(
                "id", updated.getId(),
                "status", updated.getStatus(),
                "updatedAt", updated.getUpdatedAt().toString()
        ));
    }

    private Map<String, Object> toTablePreview(CatalogueTableEntity table) {
        List<Map<String, Object>> columns = table.getColumns()
                .stream()
                .map(col -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("columnName", col.getColumnName());
                    m.put("dataType", col.getDataType() == null ? "" : col.getDataType());
                    m.put("description", col.getDescription() == null ? "" : col.getDescription());
                    m.put("businessMeaning", col.getBusinessMeaning() == null ? "" : col.getBusinessMeaning());
                    m.put("role", col.getRole() == null ? "" : col.getRole());
                    m.put("aggregationMethod", col.getAggregationMethod() == null ? "" : col.getAggregationMethod());
                    m.put("sampleValues", col.getSampleValues() == null ? "[]" : col.getSampleValues());
                    if (col.getConfidence() != null) {
                        m.put("confidence", col.getConfidence());
                    }
                    return m;
                })
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
