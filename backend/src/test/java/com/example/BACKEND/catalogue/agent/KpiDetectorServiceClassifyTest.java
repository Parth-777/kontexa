package com.example.BACKEND.catalogue.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KpiDetectorServiceClassifyTest {

    private final KpiDetectorService detector = new KpiDetectorService();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("prefers string/date-typed date column over integer epoch-like column")
    void prefersStrongDateColumn() throws Exception {
        ObjectNode table = mapper.createObjectNode();
        table.put("tableName", "events");
        table.put("tableSchema", "public");
        ArrayNode cols = table.putArray("columns");

        ObjectNode epoch = cols.addObject();
        epoch.put("columnName", "visitStartTime");
        epoch.put("dataType", "INTEGER");
        epoch.put("role", "");
        epoch.put("dateGranularity", "N/A");
        epoch.put("aggregationMethod", "NONE");
        epoch.put("container", false);

        ObjectNode date = cols.addObject();
        date.put("columnName", "date");
        date.put("dataType", "STRING");
        date.put("role", "date");
        date.put("dateGranularity", "daily");
        date.put("aggregationMethod", "NONE");
        date.put("container", false);

        ObjectNode metric = cols.addObject();
        metric.put("columnName", "totals.visits");
        metric.put("dataType", "INTEGER");
        metric.put("role", "metric");
        metric.put("dateGranularity", "N/A");
        metric.put("aggregationMethod", "SUM");
        metric.put("container", false);

        ObjectNode dim = cols.addObject();
        dim.put("columnName", "channelGrouping");
        dim.put("dataType", "STRING");
        dim.put("role", "dimension");
        dim.put("dateGranularity", "N/A");
        dim.put("aggregationMethod", "NONE");
        dim.put("container", false);

        ObjectNode container = cols.addObject();
        container.put("columnName", "totals");
        container.put("dataType", "RECORD");
        container.put("role", "container");
        container.put("container", true);

        var hints = detector.classifyColumns(table);
        assertEquals("date", hints.dateCol());
        assertTrue(hints.numericCols().contains("totals.visits"));
        assertTrue(hints.stringCols().contains("channelGrouping"));
        assertTrue(hints.stringCols().stream().noneMatch(c -> c.equals("totals")));
    }
}
