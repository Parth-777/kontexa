package com.example.BACKEND.catalogue.schema;

import com.example.BACKEND.catalogue.decision.transforms.BigQueryDialect;
import com.example.BACKEND.catalogue.decision.transforms.SnowflakeDialect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlFieldReferenceEmitterTest {

    @Test
    void emitsFlatFieldReference() {
        assertEquals("payment_type", SqlFieldReferenceEmitter.emitFieldReference("payment_type"));
    }

    @Test
    void emitsNestedFieldReference() {
        assertEquals("trafficSource.source",
                SqlFieldReferenceEmitter.emitFieldReference("trafficSource.source"));
        assertEquals("geoNetwork.country",
                SqlFieldReferenceEmitter.emitFieldReference("geoNetwork.country"));
    }

    @Test
    void emitsQuotedNestedReference() {
        assertEquals("`trafficSource`.`source`",
                SqlFieldReferenceEmitter.emitQuotedReference("trafficSource.source"));
    }

    @Test
    void emitsDialectQuotedReferences() {
        assertEquals("`trafficSource`.`source`",
                SqlFieldReferenceEmitter.emitQuotedReference(
                        "trafficSource.source", new BigQueryDialect()));
        assertEquals("\"trafficSource\".\"source\"",
                SqlFieldReferenceEmitter.emitQuotedReference(
                        "trafficSource.source", new SnowflakeDialect()));
    }

    @Test
    void emitsAliasFromNestedPath() {
        assertEquals("source", SqlFieldReferenceEmitter.emitAlias("trafficSource.source"));
    }

    @Test
    void rejectsUnsafePaths() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlFieldReferenceEmitter.emitFieldReference("geoNetwork;drop"));
    }
}
