package com.example.BACKEND.tenant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeConnectorServiceTest {

    @Test
    void sessionInitSelectsConfiguredWarehouseBeforeDiscovery() {
        List<String> sql = SnowflakeConnectorService.sessionInitSql("COMPUTE_WH", "KONTEXA_DB", "PUBLIC");

        assertEquals("ALTER SESSION SET JDBC_QUERY_RESULT_FORMAT = 'JSON'", sql.get(0));
        assertEquals("USE WAREHOUSE COMPUTE_WH", sql.get(1));
        assertEquals("USE DATABASE KONTEXA_DB", sql.get(2));
        assertEquals("USE SCHEMA PUBLIC", sql.get(3));
        assertEquals(4, sql.size());
        assertTrue(sql.stream().noneMatch(s -> s.toUpperCase().contains("INFORMATION_SCHEMA")),
                "warehouse must be selected before any metadata SELECT");
    }

    @Test
    void sessionInitUsesCallerWarehouseNotAHardcodedName() {
        List<String> sql = SnowflakeConnectorService.sessionInitSql("analytics_wh", "sales_db", "raw");
        assertEquals("USE WAREHOUSE ANALYTICS_WH", sql.get(1));
        assertEquals("USE DATABASE SALES_DB", sql.get(2));
        assertEquals("USE SCHEMA RAW", sql.get(3));
    }

    @Test
    void quoteSnowflakeIdentRejectsBlankAndEscapesQuotes() {
        assertThrows(IllegalArgumentException.class, () -> SnowflakeConnectorService.formatSnowflakeIdent(""));
        assertThrows(IllegalArgumentException.class, () -> SnowflakeConnectorService.formatSnowflakeIdent("bad\nname"));
        assertEquals("\"WEIRD\"\"WH\"", SnowflakeConnectorService.formatSnowflakeIdent("WEIRD\"WH"));
        assertEquals("\"KPNNNVP-JO94714\"", SnowflakeConnectorService.formatSnowflakeIdent("KPNNNVP-JO94714"));
    }

    @Test
    void warehouseMustNotBeTheAccountIdentifier() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> SnowflakeConnectorService.validateWarehouseNotAccount(
                        "KPNNNVP-JO94714", "KPNNNVP-JO94714"));
        assertTrue(ex.getMessage().toLowerCase().contains("account identifier"));
    }
}
