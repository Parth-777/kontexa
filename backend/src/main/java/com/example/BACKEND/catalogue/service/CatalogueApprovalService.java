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
import java.util.List;

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
        ClientCatalogueEntity catalogue = findOrThrow(catalogueId);
        catalogue.setStatus("APPROVED");
        catalogue.setUpdatedAt(LocalDateTime.now());
        catalogueRepo.save(catalogue);

        // Replace any existing snapshot for this client
        snapshotRepo.deleteByClientId(catalogue.getClientId());

        CatalogueSnapshotEntity snapshot = new CatalogueSnapshotEntity();
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
        ClientCatalogueEntity catalogue = findOrThrow(catalogueId);
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

    private ClientCatalogueEntity findOrThrow(Long id) {
        return catalogueRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Catalogue not found with id: " + id));
    }
}
