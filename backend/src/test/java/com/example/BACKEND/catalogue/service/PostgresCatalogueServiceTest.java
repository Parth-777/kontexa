package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.model.ColumnInfo;
import com.example.BACKEND.catalogue.schema.NestedSchemaFieldGraph;
import com.example.BACKEND.tenant.PostgresConnectorService;
import com.example.BACKEND.tenant.TenantCloudConnectionService.PostgresConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostgresCatalogueServiceTest {

    @Mock PostgresConnectorService connectorService;
    @InjectMocks PostgresCatalogueService catalogueService;

    @Test
    void discoverMapsInformationSchemaRowsIntoCommonCatalogueResult() {
        PostgresConfig config = sampleConfig();
        when(connectorService.listTables(
                eq("db.example.com"), eq(5432), eq("analytics"), eq("public"),
                eq("user"), eq("secret"), eq(false)))
                .thenReturn(List.of("event_log"));

        Map<String, Object> idCol = columnRow("event_id", "bigint", "int8", "NO", 1);
        Map<String, Object> metricCol = columnRow("metric_value", "numeric", "numeric", "YES", 2);
        when(connectorService.listColumns(
                eq("db.example.com"), eq(5432), eq("analytics"), eq("public"),
                eq("user"), eq("secret"), eq(false), eq("event_log")))
                .thenReturn(List.of(idCol, metricCol));
        when(connectorService.executeSelect(
                eq("db.example.com"), eq(5432), eq("analytics"), eq("public"),
                eq("user"), eq("secret"), eq(false),
                eq("SELECT COUNT(*) AS CNT FROM \"public\".\"event_log\"")))
                .thenReturn(List.of(Map.of("CNT", 12)));

        CatalogueResult result = catalogueService.discover(config, false);

        assertEquals("analytics", result.getDatabaseName());
        assertEquals("public", result.getSchemaName());
        assertEquals(1, result.getTables().size());
        assertEquals("event_log", result.getTables().getFirst().getTableName());
        assertEquals("public", result.getTables().getFirst().getTableSchema());
        assertEquals(2, result.getTables().getFirst().getColumns().size());
        ColumnInfo eventId = result.getTables().getFirst().getColumn("event_id");
        assertEquals("bigint", eventId.getDataType());
        assertFalse(eventId.isNullable());
        assertFalse(eventId.isContainer());
        ColumnInfo metric = result.getTables().getFirst().getColumn("metric_value");
        assertEquals("numeric", metric.getDataType());
        assertTrue(metric.isNullable());
        assertEquals(12L, result.getTables().getFirst().getRowCount());
        verify(connectorService, never()).listColumnsFromMetadata(
                anyString(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void jsonbAndArrayColumnsAreContainersNotNewSemanticTypes() {
        PostgresConfig config = sampleConfig();
        when(connectorService.listTables(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(List.of("event_log"));
        when(connectorService.listColumns(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(List.of(
                        columnRow("payload_json", "jsonb", "jsonb", "YES", 1),
                        columnRow("tag_list", "ARRAY", "_text", "YES", 2)
                ));
        when(connectorService.executeSelect(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(List.of(Map.of("CNT", 0)));

        CatalogueResult result = catalogueService.discover(config, false);
        ColumnInfo payload = result.getTables().getFirst().getColumn("payload_json");
        ColumnInfo tags = result.getTables().getFirst().getColumn("tag_list");
        assertTrue(payload.isContainer());
        assertEquals(NestedSchemaFieldGraph.ROLE_CONTAINER, payload.getRole());
        assertEquals("jsonb", payload.getDataType());
        assertTrue(tags.isContainer());
        assertEquals("array", tags.getDataType());
    }

    @Test
    void discoverFallsBackToJdbcMetadataWhenInformationSchemaIsEmpty() {
        PostgresConfig config = sampleConfig();
        when(connectorService.listTables(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(List.of("event_log"));
        when(connectorService.listColumns(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), eq("event_log")))
                .thenReturn(List.of());
        when(connectorService.listColumnsFromMetadata(
                eq("db.example.com"), eq(5432), eq("analytics"), eq("public"),
                eq("user"), eq("secret"), eq(false), eq("event_log")))
                .thenReturn(List.of(columnRow("metric_value", "double precision", "float8", "YES", 1)));
        when(connectorService.executeSelect(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(List.of(Map.of("CNT", 0)));

        CatalogueResult result = catalogueService.discover(config, false);

        assertEquals(1, result.getTables().getFirst().getColumns().size());
        assertEquals("metric_value", result.getTables().getFirst().getColumns().getFirst().getColumnName());
        verify(connectorService).listColumnsFromMetadata(
                eq("db.example.com"), eq(5432), eq("analytics"), eq("public"),
                eq("user"), eq("secret"), eq(false), eq("event_log"));
    }

    @Test
    void discoverRejectsIncompleteCredentials() {
        PostgresConfig config = new PostgresConfig(
                "ws", "db.example.com", 5432, "analytics", "public", "user", "", false, "postgresql://db.example.com");
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> catalogueService.discover(config, false));
        assertTrue(ex.getMessage().contains("incomplete"));
        verify(connectorService, never()).listTables(
                anyString(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void structuredTypeDetectionCoversJsonbArrayAndUdtArrays() {
        assertTrue(PostgresCatalogueService.isStructuredType("jsonb", "jsonb"));
        assertTrue(PostgresCatalogueService.isStructuredType("json", "json"));
        assertTrue(PostgresCatalogueService.isStructuredType("ARRAY", "_int4"));
        assertTrue(PostgresCatalogueService.isStructuredType("user-defined", "_text"));
        assertFalse(PostgresCatalogueService.isStructuredType("bigint", "int8"));
        assertFalse(PostgresCatalogueService.isStructuredType("text", "text"));
    }

    private static PostgresConfig sampleConfig() {
        return new PostgresConfig(
                "ws", "db.example.com", 5432, "analytics", "public",
                "user", "secret", false, "postgresql://db.example.com:5432/analytics");
    }

    private static Map<String, Object> columnRow(
            String name, String dataType, String udtName, String nullable, int ordinal
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("COLUMN_NAME", name);
        row.put("DATA_TYPE", dataType);
        row.put("UDT_NAME", udtName);
        row.put("IS_NULLABLE", nullable);
        row.put("ORDINAL_POSITION", ordinal);
        return row;
    }
}
