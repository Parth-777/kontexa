package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.entity.CatalogueColumnEntity;
import com.example.BACKEND.catalogue.entity.CatalogueTableEntity;
import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.model.ColumnInfo;
import com.example.BACKEND.catalogue.model.TableInfo;
import com.example.BACKEND.catalogue.repository.ClientCatalogueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogueStorageSemanticPersistenceTest {

    @Mock ClientCatalogueRepository catalogueRepo;

    private CatalogueStorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new CatalogueStorageService(catalogueRepo, new ObjectMapper());
        when(catalogueRepo.save(any(ClientCatalogueEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void persistsTableDescriptionAndColumnSemanticsFromCommonCatalogueResult() {
        CatalogueResult result = new CatalogueResult("ANALYTICS", "PUBLIC");
        result.setBusinessProfile("Operational activity facts.");
        result.setGlobalCaveats(List.of("Do not mix event and user grain"));
        result.setEntities(List.of(Map.of(
                "name", "event",
                "description", "One recorded activity",
                "grain", "event")));

        TableInfo table = new TableInfo("FACT_EVENTS", "PUBLIC");
        table.setDescription("Event-level activity for analysis.");
        ColumnInfo column = new ColumnInfo("EVENT_ID", "NUMBER(38,0)", false);
        column.setDescription("Unique event identifier.");
        column.setBusinessMeaning("Identifies a single recorded activity row. Use to count distinct events.");
        column.setRole("identifier");
        column.setAggregationMethod("COUNT_DISTINCT");
        column.setSynonyms(List.of("event key"));
        column.setValueMeanings(new LinkedHashMap<>());
        column.setConfidence(0.91);
        column.setDisplayUnit("count");
        column.setScaleToCanonical(1.0);
        column.setGrainLabel("events");
        column.setEnriched(true);
        table.addColumn(column);
        result.addTable(table);

        UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        storageService.save("acme", tenantId, result);

        ArgumentCaptor<ClientCatalogueEntity> captor = ArgumentCaptor.forClass(ClientCatalogueEntity.class);
        verify(catalogueRepo).save(captor.capture());
        ClientCatalogueEntity saved = captor.getValue();

        assertEquals("DRAFT", saved.getStatus());
        assertEquals("Operational activity facts.", saved.getBusinessProfile());
        assertTrue(saved.getGlobalCaveats().contains("Do not mix event and user grain"));
        assertTrue(saved.getEntitiesJson().contains("event"));

        CatalogueTableEntity tableEntity = saved.getTables().getFirst();
        assertEquals("Event-level activity for analysis.", tableEntity.getDescription());
        assertEquals("FACT_EVENTS", tableEntity.getTableName());
        assertEquals("PUBLIC", tableEntity.getTableSchema());

        CatalogueColumnEntity colEntity = tableEntity.getColumns().getFirst();
        assertEquals("EVENT_ID", colEntity.getColumnName());
        assertEquals("NUMBER(38,0)", colEntity.getDataType());
        assertEquals("Unique event identifier.", colEntity.getDescription());
        assertEquals(
                "Identifies a single recorded activity row. Use to count distinct events.",
                colEntity.getBusinessMeaning());
        assertEquals("identifier", colEntity.getRole());
        assertEquals("COUNT_DISTINCT", colEntity.getAggregationMethod());
        assertEquals("count", colEntity.getDisplayUnit());
        assertEquals(1.0, colEntity.getScaleToCanonical());
        assertEquals("events", colEntity.getGrainLabel());
        assertEquals(0.91, colEntity.getConfidence());
        assertTrue(colEntity.getSynonyms().contains("event key"));
    }
}
