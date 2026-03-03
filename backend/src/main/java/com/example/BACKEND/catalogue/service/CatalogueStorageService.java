package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.entity.CatalogueColumnEntity;
import com.example.BACKEND.catalogue.entity.CatalogueTableEntity;
import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.model.ColumnInfo;
import com.example.BACKEND.catalogue.model.TableInfo;
import com.example.BACKEND.catalogue.repository.ClientCatalogueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Persists an enriched CatalogueResult into the catalogue_* tables.
 * Call this after /build-full has returned a satisfactory catalogue.
 */
@Service
public class CatalogueStorageService {

    private final ClientCatalogueRepository catalogueRepo;
    private final ObjectMapper objectMapper;

    public CatalogueStorageService(ClientCatalogueRepository catalogueRepo,
                                   ObjectMapper objectMapper) {
        this.catalogueRepo = catalogueRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * Save a fully-enriched CatalogueResult to the database as DRAFT.
     *
     * @param clientId      an identifier for the client (e.g. "netflix", "acme_corp")
     * @param result        the enriched catalogue produced by /build-full
     * @return              the persisted entity (with generated id)
     */
    @Transactional
    public ClientCatalogueEntity save(String clientId, CatalogueResult result) {

        ClientCatalogueEntity catalogue = new ClientCatalogueEntity();
        catalogue.setClientId(clientId);
        catalogue.setDatabaseName(result.getDatabaseName());
        catalogue.setSchemaName(result.getSchemaName() != null ? result.getSchemaName() : "public");
        catalogue.setStatus("DRAFT");
        catalogue.setCreatedAt(LocalDateTime.now());
        catalogue.setUpdatedAt(LocalDateTime.now());

        for (TableInfo tableInfo : result.getTables()) {
            CatalogueTableEntity tableEntity = buildTableEntity(tableInfo, catalogue);
            catalogue.getTables().add(tableEntity);
        }

        return catalogueRepo.save(catalogue);
    }

    private CatalogueTableEntity buildTableEntity(TableInfo tableInfo,
                                                  ClientCatalogueEntity catalogue) {
        CatalogueTableEntity tableEntity = new CatalogueTableEntity();
        tableEntity.setCatalogue(catalogue);
        tableEntity.setTableName(tableInfo.getTableName());
        tableEntity.setTableSchema(tableInfo.getTableSchema() != null
                ? tableInfo.getTableSchema() : "public");
        tableEntity.setRowCount(tableInfo.getRowCount());

        for (ColumnInfo col : tableInfo.getColumns()) {
            CatalogueColumnEntity colEntity = buildColumnEntity(col, tableEntity);
            tableEntity.getColumns().add(colEntity);
        }
        return tableEntity;
    }

    private CatalogueColumnEntity buildColumnEntity(ColumnInfo col,
                                                    CatalogueTableEntity tableEntity) {
        CatalogueColumnEntity entity = new CatalogueColumnEntity();
        entity.setCatalogueTable(tableEntity);
        entity.setColumnName(col.getColumnName());
        entity.setDataType(col.getDataType());
        entity.setDescription(col.getDescription());
        entity.setRole(col.getRole());
        entity.setMinValue(col.getMinValue());
        entity.setMaxValue(col.getMaxValue());
        entity.setAvgValue(col.getAvgValue());
        entity.setEnriched(col.isEnriched());
        entity.setSkipped(col.isSkipped());
        entity.setSkipReason(col.getSkipReason());

        entity.setSampleValues(toJsonArray(col.getSampleValues()));
        entity.setSynonyms(toJsonArray(col.getSynonyms()));
        entity.setValueMeanings(toJsonObject(col.getValueMeanings()));

        return entity;
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String toJsonObject(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
