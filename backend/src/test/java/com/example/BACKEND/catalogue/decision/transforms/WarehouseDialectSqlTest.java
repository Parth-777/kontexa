package com.example.BACKEND.catalogue.decision.transforms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarehouseDialectSqlTest {

    private final BigQueryDialect bigQuery = new BigQueryDialect();
    private final SnowflakeDialect snowflake = new SnowflakeDialect();

    @Test
    void sameTemporalIntentRendersProviderSql() {
        assertEquals("EXTRACT(HOUR FROM ts)", bigQuery.extractHour("ts"));
        assertEquals("EXTRACT(HOUR FROM ts)", snowflake.extractHour("ts"));

        assertTrue(bigQuery.weekendDayType("ts").contains("EXTRACT(DAYOFWEEK FROM ts)"));
        assertTrue(snowflake.weekendDayType("ts").contains("DAYNAME(ts)"));
        assertFalse(snowflake.weekendDayType("ts").contains("FORMAT_DATE"));
    }

    @Test
    void timestampTruncAndSafeDivideAreDialectSpecific() {
        assertEquals("TIMESTAMP_TRUNC(ts, MONTH)", bigQuery.timestampTrunc("ts", "MONTH"));
        assertEquals("DATE_TRUNC('MONTH', ts)", snowflake.timestampTrunc("ts", "MONTH"));

        assertEquals("SAFE_DIVIDE(a, b)", bigQuery.safeDivide("a", "b"));
        assertEquals("DIV0NULL(a, b)", snowflake.safeDivide("a", "b"));

        assertEquals("COUNTIF(x > 0)", bigQuery.countIf("x > 0"));
        assertEquals("COUNT_IF(x > 0)", snowflake.countIf("x > 0"));
    }

    @Test
    void identifierQuotingIsDialectSpecific() {
        assertEquals("`payment_type`", bigQuery.quoteIdent("payment_type"));
        assertEquals("\"payment_type\"", snowflake.quoteIdent("payment_type"));
    }
}
