package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.tenant.SnowflakeConnectorService;
import com.example.BACKEND.tenant.TenantCloudConnectionService.SnowflakeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SnowflakeCatalogueServiceTest {

    @Mock SnowflakeConnectorService connectorService;
    @InjectMocks SnowflakeCatalogueService catalogueService;

    @Test
    void discoverBuildsCommonCatalogueFromDescribeRows() {
        SnowflakeConfig config = new SnowflakeConfig(
                "ws", "acct", "COMPUTE_WH", "KONTEXA_DB", "PUBLIC", "user", "pass", "snowflake://acct");

        when(connectorService.listTables(
                eq("acct"), eq("COMPUTE_WH"), eq("KONTEXA_DB"), eq("PUBLIC"), eq("user"), eq("pass")))
                .thenReturn(List.of("CUSTOMERS"));

        Map<String, Object> idCol = new LinkedHashMap<>();
        idCol.put("name", "CUSTOMER_ID");
        idCol.put("type", "NUMBER(38,0)");
        idCol.put("kind", "COLUMN");
        Map<String, Object> nameCol = new LinkedHashMap<>();
        nameCol.put("name", "NAME");
        nameCol.put("type", "VARCHAR(100)");
        nameCol.put("kind", "COLUMN");

        when(connectorService.executeSelect(
                eq("acct"), eq("COMPUTE_WH"), eq("KONTEXA_DB"), eq("PUBLIC"),
                eq("user"), eq("pass"), eq("DESCRIBE TABLE PUBLIC.\"CUSTOMERS\"")))
                .thenReturn(List.of(idCol, nameCol));

        when(connectorService.executeSelect(
                eq("acct"), eq("COMPUTE_WH"), eq("KONTEXA_DB"), eq("PUBLIC"),
                eq("user"), eq("pass"), eq("SELECT COUNT(*) AS CNT FROM PUBLIC.\"CUSTOMERS\"")))
                .thenReturn(List.of(Map.of("CNT", 3)));

        CatalogueResult result = catalogueService.discover(config, false);

        assertEquals("KONTEXA_DB", result.getDatabaseName());
        assertEquals("PUBLIC", result.getSchemaName());
        assertEquals(1, result.getTables().size());
        assertEquals("CUSTOMERS", result.getTables().getFirst().getTableName());
        assertEquals(2, result.getTables().getFirst().getColumns().size());
        assertTrue(result.getTables().getFirst().getColumns().stream()
                .anyMatch(c -> "CUSTOMER_ID".equalsIgnoreCase(c.getColumnName())));
        assertEquals(3L, result.getTables().getFirst().getRowCount());
    }

    @Test
    void discoverFallsBackToInformationSchemaWhenDescribeEmpty() {
        SnowflakeConfig config = new SnowflakeConfig(
                "ws", "acct", "COMPUTE_WH", "KONTEXA_DB", "PUBLIC", "user", "pass", "snowflake://acct");

        when(connectorService.listTables(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of("ORDERS"));
        when(connectorService.executeSelect(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                eq("DESCRIBE TABLE PUBLIC.\"ORDERS\"")))
                .thenReturn(List.of());

        Map<String, Object> col = new LinkedHashMap<>();
        col.put("COLUMN_NAME", "ORDER_ID");
        col.put("DATA_TYPE", "NUMBER");
        col.put("IS_NULLABLE", "NO");
        when(connectorService.executeSelect(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                eq("SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'ORDERS' ORDER BY ORDINAL_POSITION")))
                .thenReturn(List.of(col));
        when(connectorService.executeSelect(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                eq("SELECT COUNT(*) AS CNT FROM PUBLIC.\"ORDERS\"")))
                .thenReturn(List.of(Map.of("CNT", 0)));

        CatalogueResult result = catalogueService.discover(config, false);

        assertEquals(1, result.getTables().size());
        assertEquals(1, result.getTables().getFirst().getColumns().size());
        assertEquals("ORDER_ID", result.getTables().getFirst().getColumns().getFirst().getColumnName());
    }

    @Test
    void discoverRejectsIncompleteCredentials() {
        SnowflakeConfig config = new SnowflakeConfig(
                "ws", "acct", "COMPUTE_WH", "KONTEXA_DB", "PUBLIC", "user", "", "snowflake://acct");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> catalogueService.discover(config, false));
        assertTrue(ex.getMessage().contains("incomplete"));
    }
}
