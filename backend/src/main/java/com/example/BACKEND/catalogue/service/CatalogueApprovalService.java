package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.entity.CatalogueColumnEntity;
import com.example.BACKEND.catalogue.entity.CatalogueSnapshotEntity;
import com.example.BACKEND.catalogue.entity.CatalogueTableEntity;
import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.example.BACKEND.catalogue.repository.CatalogueSnapshotRepository;
import com.example.BACKEND.catalogue.repository.ClientCatalogueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private final ClientCatalogueRepository catalogueRepo;
    private final CatalogueSnapshotRepository snapshotRepo;
    private final ObjectMapper objectMapper;

    public CatalogueApprovalService(ClientCatalogueRepository catalogueRepo,
                                    CatalogueSnapshotRepository snapshotRepo,
                                    ObjectMapper objectMapper) {
        this.catalogueRepo = catalogueRepo;
        this.snapshotRepo  = snapshotRepo;
        this.objectMapper  = objectMapper;
    }

    /**
     * Approve a catalogue.
     * Flips status to APPROVED and writes the full catalogue snapshot.
     */
    @Transactional
    public ClientCatalogueEntity approve(Long catalogueId) {
        return approve(catalogueId, null);
    }

    @Transactional
    public ClientCatalogueEntity approve(Long catalogueId, String expectedClientId) {
        ClientCatalogueEntity catalogue = findOrThrow(catalogueId);
        assertClientMatch(catalogue, expectedClientId);
        catalogue.setStatus("APPROVED");
        catalogue.setUpdatedAt(LocalDateTime.now());
        catalogueRepo.save(catalogue);

        // Upsert snapshot for this client to avoid unique-key collisions on client_id.
        CatalogueSnapshotEntity snapshot = snapshotRepo.findByClientId(catalogue.getClientId())
                .orElseGet(CatalogueSnapshotEntity::new);
        snapshot.setCatalogueId(catalogue.getId());
        snapshot.setClientId(catalogue.getClientId());
        snapshot.setCatalogueJson(serializeCatalogue(catalogue));
        snapshot.setCreatedAt(LocalDateTime.now());
        snapshotRepo.save(snapshot);

        System.out.println("[Approval] Snapshot written for client: " + catalogue.getClientId());
        return catalogue;
    }

    /**
     * Reject a catalogue (needs rework / re-run).
     */
    @Transactional
    public ClientCatalogueEntity reject(Long catalogueId) {
        return reject(catalogueId, null);
    }

    @Transactional
    public ClientCatalogueEntity reject(Long catalogueId, String expectedClientId) {
        ClientCatalogueEntity catalogue = findOrThrow(catalogueId);
        assertClientMatch(catalogue, expectedClientId);
        catalogue.setStatus("REJECTED");
        catalogue.setUpdatedAt(LocalDateTime.now());
        return catalogueRepo.save(catalogue);
    }

    /**
     * List all catalogues for a given client (any status).
     */
    public List<ClientCatalogueEntity> listForClient(String clientId) {
        return catalogueRepo.findByClientId(clientId);
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
        ClientCatalogueEntity catalogue = findOrThrow(catalogueId);
        assertClientMatch(catalogue, expectedClientId);
        return catalogue;
    }

    public ClientCatalogueEntity getForTenantContext(
            Long catalogueId,
            String tenantId,
            String tenantSchema,
            String explicitClientId
    ) {
        ClientCatalogueEntity catalogue = findOrThrow(catalogueId);
        Set<String> keys = buildLookupKeys(tenantId, tenantSchema);
        addKeyVariants(keys, explicitClientId);
        if (keys.isEmpty()) {
            return catalogue;
        }
        for (String key : keys) {
            if (catalogue.getClientId().equalsIgnoreCase(key) || catalogue.getSchemaName().equalsIgnoreCase(key)) {
                return catalogue;
            }
        }
        throw new IllegalArgumentException(
                "Catalogue " + catalogueId + " does not belong to provided tenant context");
    }

    @Transactional
    public ClientCatalogueEntity updateFromTenantContext(
            Long catalogueId,
            String tenantId,
            String tenantSchema,
            String explicitClientId,
            List<Map<String, Object>> tableUpdates
    ) {
        ClientCatalogueEntity catalogue = getForTenantContext(catalogueId, tenantId, tenantSchema, explicitClientId);
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

    /**
     * Fetch the approved snapshot JSON for a client.
     * Used by the query engine — returns the raw JSON string.
     */
    public String getApprovedSnapshot(String clientId) {
        return snapshotRepo.findByClientId(clientId)
                .map(CatalogueSnapshotEntity::getCatalogueJson)
                .orElseThrow(() -> new IllegalStateException(
                        "No approved catalogue found for client: " + clientId));
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
            root.put("clientId",     catalogue.getClientId());
            root.put("schemaName",   catalogue.getSchemaName());
            root.put("databaseName", catalogue.getDatabaseName() != null
                    ? catalogue.getDatabaseName() : "");

            ArrayNode tablesArray = objectMapper.createArrayNode();

            for (CatalogueTableEntity table : catalogue.getTables()) {
                ObjectNode tableNode = objectMapper.createObjectNode();
                tableNode.put("tableName",   table.getTableName());
                tableNode.put("tableSchema", table.getTableSchema());
                tableNode.put("description", table.getDescription() != null
                        ? table.getDescription() : "");
                tableNode.put("rowCount", table.getRowCount() != null
                        ? table.getRowCount() : 0L);

                ArrayNode columnsArray = objectMapper.createArrayNode();

                for (CatalogueColumnEntity col : table.getColumns()) {
                    ObjectNode colNode = objectMapper.createObjectNode();
                    colNode.put("columnName",    col.getColumnName());
                    colNode.put("dataType",      col.getDataType() != null ? col.getDataType() : "");
                    colNode.put("description",   col.getDescription() != null ? col.getDescription() : "");
                    colNode.put("role",          col.getRole() != null ? col.getRole() : "");
                    colNode.put("synonyms",      col.getSynonyms() != null ? col.getSynonyms() : "[]");
                    colNode.put("valueMeanings", col.getValueMeanings() != null ? col.getValueMeanings() : "{}");
                    colNode.put("sampleValues",  col.getSampleValues() != null ? col.getSampleValues() : "[]");
                    colNode.put("minValue",      col.getMinValue() != null ? col.getMinValue() : "");
                    colNode.put("maxValue",      col.getMaxValue() != null ? col.getMaxValue() : "");
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

    private void upsertSnapshot(ClientCatalogueEntity catalogue) {
        CatalogueSnapshotEntity snapshot = snapshotRepo.findByClientId(catalogue.getClientId())
                .orElseGet(CatalogueSnapshotEntity::new);
        snapshot.setCatalogueId(catalogue.getId());
        snapshot.setClientId(catalogue.getClientId());
        snapshot.setCatalogueJson(serializeCatalogue(catalogue));
        snapshot.setCreatedAt(LocalDateTime.now());
        snapshotRepo.save(snapshot);
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
        if (expectedClientId == null || expectedClientId.isBlank()) return;
        if (!catalogue.getClientId().equalsIgnoreCase(expectedClientId)) {
            throw new IllegalArgumentException(
                    "Catalogue " + catalogue.getId() + " does not belong to client: " + expectedClientId);
        }
    }

}
