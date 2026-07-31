package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.entity.CatalogueColumnEntity;
import com.example.BACKEND.catalogue.schema.NestedSchemaFieldGraph;
import com.example.BACKEND.catalogue.entity.CatalogueTableEntity;
import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.model.ColumnInfo;
import com.example.BACKEND.catalogue.model.TableInfo;
import com.example.BACKEND.catalogue.repository.ClientCatalogueRepository;
import com.example.BACKEND.tenancy.TenantContext;
import com.example.BACKEND.tenancy.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
     * Dual-writes legacy {@code client_id} (slug) and UUID {@code tenant_id}
     * when {@link TenantContext} is bound.
     *
     * @param clientId      legacy identifier (workspace slug)
     * @param result        the enriched catalogue produced by /build-full
     * @return              the persisted entity (with generated id)
     */
    @Transactional
    public ClientCatalogueEntity save(String clientId, CatalogueResult result) {
        return save(clientId, resolveTenantId(), result);
    }

    @Transactional
    public ClientCatalogueEntity save(String clientId, UUID tenantId, CatalogueResult result) {

        ClientCatalogueEntity catalogue = new ClientCatalogueEntity();
        catalogue.setClientId(clientId);
        catalogue.setTenantId(tenantId);
        catalogue.setDatabaseName(result.getDatabaseName());
        catalogue.setSchemaName(result.getSchemaName() != null ? result.getSchemaName() : "public");
        catalogue.setStatus("DRAFT");
        catalogue.setCreatedAt(LocalDateTime.now());
        catalogue.setUpdatedAt(LocalDateTime.now());
        catalogue.setBusinessProfile(result.getBusinessProfile());
        catalogue.setGlobalCaveats(toJsonArray(result.getGlobalCaveats()));
        catalogue.setEntitiesJson(toJsonValue(result.getEntities()));

        for (TableInfo tableInfo : result.getTables()) {
            CatalogueTableEntity tableEntity = buildTableEntity(tableInfo, catalogue);
            catalogue.getTables().add(tableEntity);
        }

        return catalogueRepo.save(catalogue);
    }

    private static UUID resolveTenantId() {
        TenantContext ctx = TenantContextHolder.get();
        return ctx != null ? ctx.tenantId() : null;
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
        if (col.isContainer()) {
            entity.setRole(NestedSchemaFieldGraph.ROLE_CONTAINER);
        } else {
            entity.setRole(col.getRole());
        }
        entity.setMinValue(col.getMinValue());
        entity.setMaxValue(col.getMaxValue());
        entity.setAvgValue(col.getAvgValue());
        entity.setEnriched(col.isEnriched());
        entity.setSkipped(col.isSkipped());
        entity.setSkipReason(col.getSkipReason());

        entity.setSampleValues(toJsonArray(col.getSampleValues()));
        entity.setSynonyms(toJsonArray(col.getSynonyms()));
        entity.setValueMeanings(toJsonObject(col.getValueMeanings()));
        // Rich semantic SoT + optional generate-time aggregation/confidence
        entity.setBusinessMeaning(col.getBusinessMeaning());
        entity.setAggregationMethod(col.getAggregationMethod());
        entity.setConfidence(col.getConfidence());

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

    private String toJsonValue(Object value) {
        if (value == null) return "[]";
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
