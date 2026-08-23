package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.agent.StarSchemaDetector;
import com.example.BACKEND.catalogue.agent.scale.MetricRollupService;
import com.example.BACKEND.catalogue.agent.scale.ScaleProperties;
import com.example.BACKEND.catalogue.entity.CatalogueColumnEntity;
import com.example.BACKEND.catalogue.entity.CatalogueSnapshotEntity;
import com.example.BACKEND.catalogue.entity.CatalogueTableEntity;
import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.example.BACKEND.catalogue.repository.CatalogueSnapshotRepository;
import com.example.BACKEND.catalogue.repository.ClientCatalogueRepository;
import com.example.BACKEND.catalogue.schema.NestedSchemaFieldGraph;
import com.example.BACKEND.identity.auth.ForbiddenException;
import com.example.BACKEND.tenancy.TenantContext;
import com.example.BACKEND.tenancy.TenantContextHolder;
import com.example.BACKEND.tenancy.TenantOwnership;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Handles client approval / rejection of a catalogue.
 *
 * When a catalogue is APPROVED:
 *   1. Status is flipped to APPROVED in client_catalogues
 *   2. The entire catalogue is serialized into one JSON blob
 *   3. That blob is stored in catalogue_snapshots (replaces any previous snapshot)
 *
 * The snapshot is what the query engine reads at runtime — one fast DB read
 * instead of joining across three tables.
 */
@Service
public class CatalogueApprovalService {

    private static final Logger log = LoggerFactory.getLogger(CatalogueApprovalService.class);

    private final ClientCatalogueRepository catalogueRepo;
    private final CatalogueSnapshotRepository snapshotRepo;
    private final ObjectMapper objectMapper;
    private final CatalogueSemanticEnricher semanticEnricher;
    private final StarSchemaDetector starSchemaDetector;
    private final MetricRollupService metricRollupService;
    private final ScaleProperties scaleProperties;
    private final TenantBusinessProfileService tenantBusinessProfileService;

    public CatalogueApprovalService(ClientCatalogueRepository catalogueRepo,
                                    CatalogueSnapshotRepository snapshotRepo,
                                    ObjectMapper objectMapper,
                                    CatalogueSemanticEnricher semanticEnricher,
                                    StarSchemaDetector starSchemaDetector,
                                    @Lazy MetricRollupService metricRollupService,
                                    ScaleProperties scaleProperties,
                                    TenantBusinessProfileService tenantBusinessProfileService) {
        this.catalogueRepo      = catalogueRepo;
        this.snapshotRepo       = snapshotRepo;
        this.objectMapper       = objectMapper;
        this.semanticEnricher   = semanticEnricher;
        this.starSchemaDetector = starSchemaDetector;
        this.metricRollupService = metricRollupService;
        this.scaleProperties    = scaleProperties;
        this.tenantBusinessProfileService = tenantBusinessProfileService;
    }

    /**
     * Approve a catalogue owned by {@code expectedClientId}.
     * Flips status to APPROVED and writes the full catalogue snapshot.
     */
    @Transactional
    public ClientCatalogueEntity approve(Long catalogueId, String expectedClientId) {
        ClientCatalogueEntity catalogue = requireOwned(catalogueId, expectedClientId);
        catalogue.setStatus("APPROVED");
        catalogue.setUpdatedAt(LocalDateTime.now());
        catalogueRepo.save(catalogue);

        // Enrich column semantics with LLM (one call per table, best-effort)
        try {
            semanticEnricher.enrich(catalogue);
            catalogueRepo.save(catalogue); // persist enriched column fields
        } catch (Exception e) {
            System.out.println("[Approval] Semantic enrichment failed (non-fatal): " + e.getMessage());
        }

        // Classify tables as FACT / DIMENSION / UNKNOWN and detect dimension joins (best-effort)
        try {
            starSchemaDetector.detect(catalogue);
            catalogueRepo.save(catalogue); // persist tableRole on each table
        } catch (Exception e) {
            System.out.println("[Approval] Star schema detection failed (non-fatal): " + e.getMessage());
        }

        persistSnapshotForCatalogue(catalogue);

        System.out.println("[Approval] Snapshot written for client: " + catalogue.getClientId()
                + " catalogueId=" + catalogue.getId());

        try {
            tenantBusinessProfileService.inferFromCatalogue(catalogue);
        } catch (Exception e) {
            System.out.println("[Approval] Business profile inference failed (non-fatal): " + e.getMessage());
        }

        if (scaleProperties.isRollupEnabled()) {
            try {
                metricRollupService.buildRollupsForClient(catalogue.getClientId());
            } catch (Exception e) {
                System.out.println("[Approval] Rollup build failed (non-fatal): " + e.getMessage());
            }
        }

        return catalogue;
    }

    /**
     * Reject a catalogue owned by {@code expectedClientId}.
     */
    @Transactional
    public ClientCatalogueEntity reject(Long catalogueId, String expectedClientId) {
        ClientCatalogueEntity catalogue = requireOwned(catalogueId, expectedClientId);
        catalogue.setStatus("REJECTED");
        catalogue.setUpdatedAt(LocalDateTime.now());
        return catalogueRepo.save(catalogue);
    }

    /**
     * List all catalogues for a given client (any status).
     * Kept for DecisionRuntime adapters that still key by workspace slug.
     */
    public List<ClientCatalogueEntity> listForClient(String clientId) {
        return catalogueRepo.findByClientId(clientId);
    }

    /** Prefer UUID tenant_id; fall back to legacy slug when unmapped rows remain. */
    public List<ClientCatalogueEntity> listForTenant(TenantContext ctx) {
        List<ClientCatalogueEntity> byId = catalogueRepo.findByTenantId(ctx.tenantId());
        if (!byId.isEmpty()) {
            return byId;
        }
        return catalogueRepo.findByClientId(ctx.legacyTenantKey());
    }

    /**
     * Tenant-centric listing: resolves catalogues by multiple possible identifiers
     * (client id, schema name, and common tenant-login prefixes).
     */
    public List<ClientCatalogueEntity> listForTenantContext(String tenantId, String tenantSchema) {
        Set<String> keys = buildLookupKeys(tenantId, tenantSchema);
        LinkedHashSet<ClientCatalogueEntity> merged = new LinkedHashSet<>();
        for (String key : keys) {
            merged.addAll(catalogueRepo.findByClientIdIgnoreCase(key));
            merged.addAll(catalogueRepo.findBySchemaNameIgnoreCase(key));
        }
        return merged.stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .toList();
    }

    public ClientCatalogueEntity getForClient(Long catalogueId, String expectedClientId) {
        return requireOwned(catalogueId, expectedClientId);
    }

    /**
     * Load a catalogue by id only if it belongs to the authenticated tenant.
     * Resource id alone is never sufficient.
     */
    public ClientCatalogueEntity requireOwned(Long catalogueId, TenantContext ctx) {
        ClientCatalogueEntity catalogue = findOrThrow(catalogueId);
        TenantOwnership.requireMatch(
                catalogue.getTenantId(), catalogue.getClientId(), ctx, "Catalogue " + catalogueId);
        // Heal missing tenant_id on read path (post-backfill safety).
        if (catalogue.getTenantId() == null) {
            catalogue.setTenantId(ctx.tenantId());
            catalogueRepo.save(catalogue);
        }
        return catalogue;
    }

    /**
     * Load a catalogue by id only if it belongs to {@code ownerClientId} (legacy slug).
     * When a request-bound {@link TenantContext} exists, UUID ownership is preferred.
     */
    public ClientCatalogueEntity requireOwned(Long catalogueId, String ownerClientId) {
        TenantContext ctx = TenantContextHolder.get();
        if (ctx != null) {
            return requireOwned(catalogueId, ctx);
        }
        if (ownerClientId == null || ownerClientId.isBlank()) {
            throw new ForbiddenException("Authenticated tenant key is required");
        }
        ClientCatalogueEntity catalogue = findOrThrow(catalogueId);
        assertClientMatch(catalogue, ownerClientId);
        return catalogue;
    }

    @Transactional
    public ClientCatalogueEntity updateOwned(
            Long catalogueId,
            TenantContext ctx,
            List<Map<String, Object>> tableUpdates
    ) {
        ClientCatalogueEntity catalogue = requireOwned(catalogueId, ctx);
        if (tableUpdates != null) {
            applyTableUpdates(catalogue, tableUpdates);
        }
        catalogue.setUpdatedAt(LocalDateTime.now());
        if (catalogue.getTenantId() == null) {
            catalogue.setTenantId(ctx.tenantId());
        }
        ClientCatalogueEntity saved = catalogueRepo.save(catalogue);

        if ("APPROVED".equalsIgnoreCase(saved.getStatus())) {
            upsertSnapshot(saved);
        }
        return saved;
    }

    @Transactional
    public ClientCatalogueEntity updateOwned(
            Long catalogueId,
            String ownerClientId,
            List<Map<String, Object>> tableUpdates
    ) {
        TenantContext ctx = TenantContextHolder.get();
        if (ctx != null) {
            return updateOwned(catalogueId, ctx, tableUpdates);
        }
        ClientCatalogueEntity catalogue = requireOwned(catalogueId, ownerClientId);
        if (tableUpdates != null) {
            applyTableUpdates(catalogue, tableUpdates);
        }
        catalogue.setUpdatedAt(LocalDateTime.now());
        ClientCatalogueEntity saved = catalogueRepo.save(catalogue);

        if ("APPROVED".equalsIgnoreCase(saved.getStatus())) {
            upsertSnapshot(saved);
        }
        return saved;
    }

    /** @deprecated Prefer {@link #requireOwned(Long, String)} — client keys must come from TenantContext. */
    @Deprecated
    public ClientCatalogueEntity getForTenantContext(
            Long catalogueId,
            String tenantId,
            String tenantSchema,
            String explicitClientId
    ) {
        String owner = firstNonBlank(explicitClientId, tenantId, tenantSchema);
        return requireOwned(catalogueId, owner);
    }

    /** @deprecated Prefer {@link #updateOwned(Long, String, List)}. */
    @Deprecated
    @Transactional
    public ClientCatalogueEntity updateFromTenantContext(
            Long catalogueId,
            String tenantId,
            String tenantSchema,
            String explicitClientId,
            List<Map<String, Object>> tableUpdates
    ) {
        String owner = firstNonBlank(explicitClientId, tenantId, tenantSchema);
        return updateOwned(catalogueId, owner, tableUpdates);
    }

    /**
     * Single choke point for Deep Analysis / query-engine snapshot JSON.
     * Resolves the active APPROVED catalogue first, then that catalogue's snapshot.
     */
    @Transactional(readOnly = true)
    public String getApprovedSnapshot(String clientId) {
        ClientCatalogueEntity active = requireActiveApprovedCatalogue(clientId);
        CatalogueSnapshotEntity snapshot = findSnapshotForActiveCatalogue(active);
        String json;
        Long snapshotId = null;
        Long snapshotCatalogueId = null;
        if (snapshot != null) {
            json = snapshot.getCatalogueJson();
            snapshotId = snapshot.getId();
            snapshotCatalogueId = snapshot.getCatalogueId();
        } else {
            log.warn("[catalogue-retrieval] no matching snapshot for active catalogueId={} — serializing entity",
                    active.getId());
            json = serializeCatalogue(active);
        }
        logRetrieval(clientId, active, snapshotId, snapshotCatalogueId, json);
        return json;
    }

    /**
     * Canonical runtime selection: newest APPROVED catalogue for the tenant.
     * UUID-scoped when {@link TenantContext} is bound; otherwise slug/client-id.
     * Historical APPROVED rows are not deleted.
     */
    @Transactional(readOnly = true)
    public ClientCatalogueEntity requireActiveApprovedCatalogue(String tenantKey) {
        TenantContext ctx = TenantContextHolder.get();
        List<ClientCatalogueEntity> candidates;
        if (ctx != null) {
            candidates = listForTenant(ctx);
            if (candidates.isEmpty() && tenantKey != null && !tenantKey.isBlank()) {
                candidates = listForClient(tenantKey);
            }
        } else if (tenantKey != null && !tenantKey.isBlank()) {
            candidates = listForClient(tenantKey);
        } else {
            candidates = List.of();
        }
        return pickLatestApproved(candidates)
                .orElseThrow(() -> new IllegalStateException(
                        "No approved catalogue found for tenant: "
                                + (tenantKey == null ? "(none)" : tenantKey)
                                + ". Please approve a catalogue in the admin dashboard."));
    }

    /**
     * Get the approved ClientCatalogueEntity (still needed by CataloguePromptBuilder).
     */
    public ClientCatalogueEntity getApproved(String clientId) {
        return catalogueRepo.findByClientIdAndStatus(clientId, "APPROVED")
                .orElseThrow(() -> new IllegalStateException(
                        "No approved catalogue found for client: " + clientId));
    }

    // ── Private ──────────────────────────────────────────────────────

    /**
     * Serialize the full catalogue (tables + columns) into one JSON string.
     * This is what gets stored in catalogue_snapshots.catalogue_json.
     */
    private String serializeCatalogue(ClientCatalogueEntity catalogue) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            // catalogueId anchors the published blob to a specific relational version
            // (future multi-version evolution can keep history without reshaping JSON).
            root.put("catalogueId",  catalogue.getId());
            root.put("clientId",     catalogue.getClientId());
            root.put("schemaName",   catalogue.getSchemaName());
            root.put("databaseName", catalogue.getDatabaseName() != null
                    ? catalogue.getDatabaseName() : "");
            if (catalogue.getLineageKey() != null && !catalogue.getLineageKey().isBlank()) {
                root.put("lineageKey", catalogue.getLineageKey());
            }
            if (catalogue.getSupersedesCatalogueId() != null) {
                root.put("supersedesCatalogueId", catalogue.getSupersedesCatalogueId());
            }
            root.put("businessProfile", catalogue.getBusinessProfile() != null
                    ? catalogue.getBusinessProfile() : "");
            // Stored as JSON text on the entity; re-emit as raw JSON nodes when valid.
            root.set("globalCaveats", readJsonOrEmptyArray(catalogue.getGlobalCaveats()));
            root.set("entities", readJsonOrEmptyArray(catalogue.getEntitiesJson()));

            ArrayNode tablesArray = objectMapper.createArrayNode();

            for (CatalogueTableEntity table : catalogue.getTables()) {
                ObjectNode tableNode = objectMapper.createObjectNode();
                tableNode.put("tableName",   table.getTableName());
                tableNode.put("tableSchema", table.getTableSchema());
                tableNode.put("tableRole",   table.getTableRole() != null
                        ? table.getTableRole() : "UNKNOWN");
                tableNode.put("description", table.getDescription() != null
                        ? table.getDescription() : "");
                tableNode.put("rowCount", table.getRowCount() != null
                        ? table.getRowCount() : 0L);

                ArrayNode columnsArray = objectMapper.createArrayNode();

                for (CatalogueColumnEntity col : table.getColumns()) {
                    ObjectNode colNode = objectMapper.createObjectNode();
                    colNode.put("columnName",       col.getColumnName());
                    colNode.put("dataType",         col.getDataType() != null ? col.getDataType() : "");
                    colNode.put("description",      col.getDescription() != null ? col.getDescription() : "");
                    colNode.put("role",             col.getRole() != null ? col.getRole() : "");
                    boolean container = NestedSchemaFieldGraph.ROLE_CONTAINER.equalsIgnoreCase(col.getRole())
                            || NestedSchemaFieldGraph.isNestedContainerType(col.getDataType());
                    colNode.put("container", container);
                    String parentPath = NestedSchemaFieldGraph.parentPath(col.getColumnName());
                    if (parentPath != null) {
                        colNode.put("parentPath", parentPath);
                    }
                    colNode.put("synonyms",         col.getSynonyms() != null ? col.getSynonyms() : "[]");
                    colNode.put("valueMeanings",    col.getValueMeanings() != null ? col.getValueMeanings() : "{}");
                    colNode.put("sampleValues",     col.getSampleValues() != null ? col.getSampleValues() : "[]");
                    colNode.put("minValue",         col.getMinValue() != null ? col.getMinValue() : "");
                    colNode.put("maxValue",         col.getMaxValue() != null ? col.getMaxValue() : "");
                    // Semantic enrichment — businessMeaning is the primary meaning SoT
                    colNode.put("aggregationMethod", col.getAggregationMethod() != null ? col.getAggregationMethod() : "NONE");
                    colNode.put("businessMeaning",   col.getBusinessMeaning()   != null ? col.getBusinessMeaning()   : "");
                    colNode.put("comparisonPeriod",  col.getComparisonPeriod()  != null ? col.getComparisonPeriod()  : "NONE");
                    colNode.put("dateGranularity",   col.getDateGranularity()   != null ? col.getDateGranularity()   : "N/A");
                    if (col.getDisplayUnit() != null && !col.getDisplayUnit().isBlank()) {
                        colNode.put("displayUnit", col.getDisplayUnit());
                    }
                    if (col.getScaleToCanonical() != null) {
                        colNode.put("scaleToCanonical", col.getScaleToCanonical());
                    }
                    if (col.getGrainLabel() != null && !col.getGrainLabel().isBlank()) {
                        colNode.put("grainLabel", col.getGrainLabel());
                    }
                    if (col.getConfidence() != null) {
                        colNode.put("confidence", col.getConfidence());
                    }
                    columnsArray.add(colNode);
                }

                tableNode.set("columns", columnsArray);
                tablesArray.add(tableNode);
            }

            root.set("tables", tablesArray);
            return objectMapper.writeValueAsString(root);

        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize catalogue to JSON: " + e.getMessage(), e);
        }
    }

    private JsonNode readJsonOrEmptyArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            return node != null ? node : objectMapper.createArrayNode();
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }

    private void upsertSnapshot(ClientCatalogueEntity catalogue) {
        persistSnapshotForCatalogue(catalogue);
    }

    /**
     * Publish this catalogue into the unique snapshot slot for the workspace.
     * Latest approve wins: if a snapshot already exists for this client/tenant,
     * update that row in place (never INSERT a second row — unique client_id /
     * tenant_id would fail and Hibernate IDENTITY would leave a null id).
     */
    private void persistSnapshotForCatalogue(ClientCatalogueEntity catalogue) {
        CatalogueSnapshotEntity snapshot = findSnapshotToWrite(catalogue)
                .orElseGet(CatalogueSnapshotEntity::new);
        Long previousCatalogueId = snapshot.getCatalogueId();
        applySnapshotIdentity(snapshot, catalogue);
        snapshotRepo.save(snapshot);
        if (previousCatalogueId != null && !previousCatalogueId.equals(catalogue.getId())) {
            log.info("[catalogue-retrieval] replaced snapshot slot snapshotId={} from catalogueId={} to catalogueId={}",
                    snapshot.getId(), previousCatalogueId, catalogue.getId());
        }
    }

    private void applySnapshotIdentity(CatalogueSnapshotEntity snapshot, ClientCatalogueEntity catalogue) {
        snapshot.setCatalogueId(catalogue.getId());
        snapshot.setClientId(catalogue.getClientId());
        snapshot.setTenantId(catalogue.getTenantId());
        snapshot.setCatalogueJson(serializeCatalogue(catalogue));
        snapshot.setCreatedAt(LocalDateTime.now());
    }

    /**
     * Snapshot row to update on approve: same catalogue_id first, otherwise the
     * existing unique client/tenant slot so the latest approve replaces it.
     */
    private Optional<CatalogueSnapshotEntity> findSnapshotToWrite(ClientCatalogueEntity catalogue) {
        if (catalogue.getId() != null) {
            Optional<CatalogueSnapshotEntity> byCatalogue =
                    snapshotRepo.findFirstByCatalogueIdOrderByIdDesc(catalogue.getId());
            if (byCatalogue.isPresent()) {
                return byCatalogue;
            }
        }
        if (catalogue.getTenantId() != null) {
            Optional<CatalogueSnapshotEntity> byTenant =
                    snapshotRepo.findByTenantId(catalogue.getTenantId());
            if (byTenant.isPresent()) {
                return byTenant;
            }
        }
        if (catalogue.getClientId() != null && !catalogue.getClientId().isBlank()) {
            return snapshotRepo.findByClientId(catalogue.getClientId());
        }
        return Optional.empty();
    }

    private CatalogueSnapshotEntity findSnapshotForActiveCatalogue(ClientCatalogueEntity active) {
        Optional<CatalogueSnapshotEntity> byCatalogue =
                snapshotRepo.findFirstByCatalogueIdOrderByIdDesc(active.getId());
        if (byCatalogue.isPresent()) {
            return byCatalogue.get();
        }
        Optional<CatalogueSnapshotEntity> legacy = Optional.empty();
        if (active.getTenantId() != null) {
            legacy = snapshotRepo.findByTenantId(active.getTenantId());
        }
        if (legacy.isEmpty() && active.getClientId() != null) {
            legacy = snapshotRepo.findByClientId(active.getClientId());
        }
        if (legacy.isPresent() && active.getId().equals(legacy.get().getCatalogueId())) {
            return legacy.get();
        }
        if (legacy.isPresent()) {
            log.warn("[catalogue-retrieval] ignoring stale snapshot id={} snapshotCatalogueId={} activeCatalogueId={}",
                    legacy.get().getId(), legacy.get().getCatalogueId(), active.getId());
        }
        return null;
    }

    private Optional<ClientCatalogueEntity> pickLatestApproved(List<ClientCatalogueEntity> catalogues) {
        if (catalogues == null || catalogues.isEmpty()) {
            return Optional.empty();
        }
        return catalogues.stream()
                .filter(c -> c != null && "APPROVED".equalsIgnoreCase(c.getStatus()))
                .max(Comparator.comparing(
                        c -> c.getUpdatedAt() != null ? c.getUpdatedAt() : c.getCreatedAt()));
    }

    private void logRetrieval(
            String tenantKey,
            ClientCatalogueEntity active,
            Long snapshotId,
            Long snapshotCatalogueId,
            String json
    ) {
        TenantContext ctx = TenantContextHolder.get();
        log.info("[catalogue-retrieval] slug={} tenantUuid={} catalogueId={} status={} updatedAt={} "
                        + "snapshotId={} snapshotCatalogueId={} tables={}",
                tenantKey,
                ctx != null ? ctx.tenantId() : (active.getTenantId() != null ? active.getTenantId() : ""),
                active.getId(),
                active.getStatus(),
                active.getUpdatedAt(),
                snapshotId,
                snapshotCatalogueId,
                tableNamesFromSnapshotJson(json));
    }

    private String tableNamesFromSnapshotJson(String json) {
        try {
            JsonNode tables = objectMapper.readTree(json == null ? "{}" : json).path("tables");
            if (!tables.isArray()) {
                return "[]";
            }
            List<String> names = new ArrayList<>();
            for (JsonNode table : tables) {
                String schema = table.path("tableSchema").asText("");
                String name = table.path("tableName").asText("");
                names.add(schema.isBlank() ? name : schema + "." + name);
            }
            return names.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private void applyTableUpdates(ClientCatalogueEntity catalogue, List<Map<String, Object>> tableUpdates) {
        for (Map<String, Object> t : tableUpdates) {
            String tableName = str(t.get("tableName"));
            if (tableName == null) continue;
            String tableSchema = str(t.get("tableSchema"));
            CatalogueTableEntity targetTable = catalogue.getTables().stream()
                    .filter(x -> x.getTableName().equalsIgnoreCase(tableName)
                            && (tableSchema == null || x.getTableSchema().equalsIgnoreCase(tableSchema)))
                    .findFirst()
                    .orElse(null);
            if (targetTable == null) continue;

            String tableDesc = str(t.get("description"));
            if (tableDesc != null) {
                targetTable.setDescription(tableDesc);
            }

            Object cols = t.get("columns");
            if (!(cols instanceof List<?> colList)) continue;
            for (Object obj : colList) {
                if (!(obj instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> colUpdate = (Map<String, Object>) raw;
                String colName = str(colUpdate.get("columnName"));
                if (colName == null) continue;
                CatalogueColumnEntity targetCol = targetTable.getColumns().stream()
                        .filter(c -> c.getColumnName().equalsIgnoreCase(colName))
                        .findFirst()
                        .orElse(null);
                if (targetCol == null) continue;
                String desc = str(colUpdate.get("description"));
                String role = str(colUpdate.get("role"));
                if (desc != null) targetCol.setDescription(desc);
                if (role != null) targetCol.setRole(role);
            }
        }
    }

    private String str(Object value) {
        if (value == null) return null;
        return String.valueOf(value);
    }

    private ClientCatalogueEntity findOrThrow(Long id) {
        return catalogueRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Catalogue not found with id: " + id));
    }

    private Set<String> buildLookupKeys(String tenantId, String tenantSchema) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        addKeyVariants(keys, tenantId);
        addKeyVariants(keys, tenantSchema);
        return keys;
    }

    private void addKeyVariants(Set<String> keys, String raw) {
        if (raw == null) return;
        String base = raw.trim();
        if (base.isBlank()) return;
        keys.add(base);
        keys.add(base.toLowerCase(Locale.ROOT));

        int dash = base.indexOf('-');
        if (dash > 0) {
            String prefix = base.substring(0, dash).trim();
            if (!prefix.isBlank()) {
                keys.add(prefix);
                keys.add(prefix.toLowerCase(Locale.ROOT));
            }
        }
    }

    private void assertClientMatch(ClientCatalogueEntity catalogue, String expectedClientId) {
        if (expectedClientId == null || expectedClientId.isBlank()) {
            throw new ForbiddenException("Authenticated tenant key is required");
        }
        String owner = catalogue.getClientId();
        String schema = catalogue.getSchemaName();
        boolean match = owner != null && owner.equalsIgnoreCase(expectedClientId.trim());
        if (!match && schema != null && schema.equalsIgnoreCase(expectedClientId.trim())) {
            match = true;
        }
        if (!match) {
            throw new ForbiddenException(
                    "Catalogue " + catalogue.getId() + " does not belong to authenticated tenant");
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

}
