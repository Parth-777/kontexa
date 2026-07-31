package com.example.BACKEND.catalogue.schema;

import com.example.BACKEND.catalogue.schema.NestedSchemaDiscoverer.SnowflakeDescribeRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestedSchemaDiscovererTest {

    @Test
    void discoversSnowflakeNestedPathsFromDescribeRows() {
        List<NestedSchemaDiscoverer.DiscoveredField> fields = NestedSchemaDiscoverer.discoverSnowflakeDescribeRows(
                List.of(
                        new SnowflakeDescribeRow("TRAFFICSOURCE", "OBJECT", "COLUMN"),
                        new SnowflakeDescribeRow("TRAFFICSOURCE:source", "TEXT", "COLUMN"),
                        new SnowflakeDescribeRow("TRAFFICSOURCE:medium", "TEXT", "COLUMN")));

        assertTrue(fields.stream().anyMatch(f ->
                "TRAFFICSOURCE".equalsIgnoreCase(f.fieldPath()) && f.container()));
        assertTrue(fields.stream().anyMatch(f ->
                "TRAFFICSOURCE.source".equalsIgnoreCase(f.fieldPath()) && !f.container()));
        assertTrue(fields.stream().anyMatch(f ->
                "TRAFFICSOURCE.medium".equalsIgnoreCase(f.fieldPath()) && !f.container()));
    }

    @Test
    void joinPathBuildsDottedPaths() {
        assertEquals("geoNetwork.country", NestedSchemaFieldGraph.joinPath("geoNetwork", "country"));
    }

    @Test
    void containerDetectionIsGeneric() {
        assertTrue(NestedSchemaFieldGraph.isNestedContainerType("RECORD"));
        assertTrue(NestedSchemaFieldGraph.isNestedContainerType("STRUCT"));
        assertFalse(NestedSchemaFieldGraph.isNestedContainerType("STRING"));
    }
}
