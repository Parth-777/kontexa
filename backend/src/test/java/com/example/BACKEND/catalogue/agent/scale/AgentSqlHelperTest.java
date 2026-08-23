package com.example.BACKEND.catalogue.agent.scale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSqlHelperTest {

    @Test
    @DisplayName("BigQuery nested leaf paths are quoted per segment")
    void qualifyNestedBigQueryPath() {
        assertEquals("`totals`.`visits`",
                AgentSqlHelper.qualify("totals.visits", "bigquery"));
        assertEquals("`trafficSource`.`medium`",
                AgentSqlHelper.qualify("trafficSource.medium", "bigquery"));
        assertEquals("`channelGrouping`",
                AgentSqlHelper.qualify("channelGrouping", "bigquery"));
        assertFalse(AgentSqlHelper.qualify("totals.visits", "bigquery").contains("`totals.visits`"));
    }

    @Test
    @DisplayName("date expression handles epoch and compact YYYYMMDD without hard INT64 DATE cast")
    void asDateExprIsSafeForEpochAndYyyymmdd() {
        String expr = AgentSqlHelper.asDateExpr("`date`", "bigquery");
        assertTrue(expr.contains("TIMESTAMP_SECONDS"));
        assertTrue(expr.contains("PARSE_DATE('%Y%m%d'"));
        assertTrue(expr.contains("SAFE_CAST"));
        assertTrue(expr.contains("COALESCE"));
    }
}
