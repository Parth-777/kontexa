package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.llm.ChatCompletionResult;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.example.BACKEND.catalogue.llm.PromptBuilder;
import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.model.ColumnInfo;
import com.example.BACKEND.catalogue.model.TableInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogueEnrichmentCommonPipelineTest {

    @Mock OpenAiClient openAiClient;

    private CatalogueEnrichmentService enrichmentService;

    @BeforeEach
    void setUp() {
        enrichmentService = new CatalogueEnrichmentService(openAiClient, new PromptBuilder());
    }

    @Test
    void snowflakeShapedResultReceivesSameSemanticFieldsAsBigQueryShapedResult() {
        when(openAiClient.chatDetailed(anyString(), anyString(), anyInt()))
                .thenReturn(okJson("""
                        {
                          "businessProfile": "Event-level activity for analysis.",
                          "entities": [{"name": "event", "description": "One recorded activity", "grain": "event"}],
                          "globalCaveats": ["Do not treat event id as a user id"],
                          "columns": [
                            {
                              "columnName": "EVENT_ID",
                              "description": "Unique event identifier.",
                              "businessMeaning": "Identifies a single recorded activity row. Use to count distinct events, never as a user grain. Do not sum this identifier. One value corresponds to one event occurrence.",
                              "synonyms": ["event key"],
                              "valueMeanings": {},
                              "role": "identifier",
                              "aggregationMethod": "COUNT_DISTINCT",
                              "confidence": 0.9,
                              "displayUnit": "count",
                              "scaleToCanonical": 1,
                              "grainLabel": "events"
                            },
                            {
                              "columnName": "EVENT_TS",
                              "description": "When the event occurred.",
                              "businessMeaning": "Timestamp of the activity. Use as the time axis for trends. Do not aggregate as a metric. Grain is one event. Compare with care across time zones.",
                              "role": "timestamp",
                              "aggregationMethod": "NONE",
                              "confidence": 0.88
                            }
                          ]
                        }
                        """));

        CatalogueResult snowflakeShaped = warehouseResult(
                "ANALYTICS", "PUBLIC", "FACT_EVENTS",
                col("EVENT_ID", "NUMBER(38,0)"),
                col("EVENT_TS", "TIMESTAMP_NTZ"));
        CatalogueResult bigQueryShaped = warehouseResult(
                "proj", "dataset", "fact_events",
                col("EVENT_ID", "INTEGER"),
                col("EVENT_TS", "TIMESTAMP"));

        enrichmentService.enrich(snowflakeShaped);
        enrichmentService.enrich(bigQueryShaped);

        assertSemanticParity(snowflakeShaped, "EVENT_ID");
        assertSemanticParity(bigQueryShaped, "EVENT_ID");
        assertEquals(
                snowflakeShaped.getTables().getFirst().getColumn("EVENT_ID").getRole(),
                bigQueryShaped.getTables().getFirst().getColumn("EVENT_ID").getRole());
        assertEquals(
                snowflakeShaped.getTables().getFirst().getDescription(),
                bigQueryShaped.getTables().getFirst().getDescription());
        assertEquals("Event-level activity for analysis.", snowflakeShaped.getBusinessProfile());
    }

    @Test
    void postgresShapedResultReceivesSameCommonSemanticFields() {
        when(openAiClient.chatDetailed(anyString(), anyString(), anyInt()))
                .thenReturn(okJson("""
                        {
                          "businessProfile": "Event-level activity for analysis.",
                          "entities": [{"name": "event", "description": "One recorded activity", "grain": "event"}],
                          "globalCaveats": ["Do not treat event id as a user id"],
                          "columns": [
                            {
                              "columnName": "EVENT_ID",
                              "description": "Unique event identifier.",
                              "businessMeaning": "Identifies a single recorded activity row. Use to count distinct events, never as a user grain. Do not sum this identifier. One value corresponds to one event occurrence.",
                              "synonyms": ["event key"],
                              "valueMeanings": {},
                              "role": "identifier",
                              "aggregationMethod": "COUNT_DISTINCT",
                              "confidence": 0.9,
                              "displayUnit": "count",
                              "scaleToCanonical": 1,
                              "grainLabel": "events"
                            },
                            {
                              "columnName": "EVENT_TS",
                              "description": "When the event occurred.",
                              "businessMeaning": "Timestamp of the activity. Use as the time axis for trends. Do not aggregate as a metric. Grain is one event. Compare with care across time zones.",
                              "role": "timestamp",
                              "aggregationMethod": "NONE",
                              "confidence": 0.88
                            }
                          ]
                        }
                        """));

        CatalogueResult postgresShaped = warehouseResult(
                "analytics", "public", "event_log",
                col("EVENT_ID", "bigint"),
                col("EVENT_TS", "timestamp with time zone"));

        enrichmentService.enrich(postgresShaped);
        assertSemanticParity(postgresShaped, "EVENT_ID");
        assertEquals("Event-level activity for analysis.", postgresShaped.getBusinessProfile());
        assertEquals("identifier", postgresShaped.getTables().getFirst().getColumn("EVENT_ID").getRole());
        assertEquals("COUNT_DISTINCT", postgresShaped.getTables().getFirst().getColumn("EVENT_ID").getAggregationMethod());
    }

    @Test
    void mapsSnakeCaseKeysQualifiedNamesAndObjectColumnsIntoTableAndColumnSemantics() {
        when(openAiClient.chatDetailed(anyString(), anyString(), anyInt()))
                .thenReturn(okJson("""
                        Here is the catalogue:
                        ```json
                        {
                          "business_profile": "Widget inventory snapshots.",
                          "global_caveats": ["Snapshot grain, not a transaction log"],
                          "entities": [{"name": "widget", "description": "Stocked item", "grain": "sku"}],
                          "columns": {
                            "PUBLIC.WIDGET_QTY": {
                              "column_name": "WIDGET_QTY",
                              "description": "On-hand quantity.",
                              "business_meaning": "Units currently in stock for this SKU. Use to measure inventory depth. Do not treat as sales. Aggregate with SUM at the SKU grain. Avoid mixing with inbound receipts.",
                              "role": "metric",
                              "aggregation_method": "SUM",
                              "confidence": 0.81,
                              "display_unit": "count",
                              "grain_label": "units"
                            }
                          }
                        }
                        ```
                        """));

        CatalogueResult result = warehouseResult(
                "WH", "PUBLIC", "WIDGET_SNAPSHOT",
                col("WIDGET_QTY", "NUMBER(38,0)"));
        enrichmentService.enrich(result);

        TableInfo table = result.getTables().getFirst();
        ColumnInfo column = table.getColumn("WIDGET_QTY");
        assertEquals("Widget inventory snapshots.", table.getDescription());
        assertEquals("Widget inventory snapshots.", result.getBusinessProfile());
        assertEquals("On-hand quantity.", column.getDescription());
        assertTrue(column.getBusinessMeaning().contains("inventory"));
        assertEquals("metric", column.getRole());
        assertEquals("SUM", column.getAggregationMethod());
        assertEquals("count", column.getDisplayUnit());
        assertEquals("units", column.getGrainLabel());
        assertEquals(1, result.getGlobalCaveats().size());
    }

    @Test
    void unknownColumnNamesAreMappingFailuresNotSilentSuccess() {
        when(openAiClient.chatDetailed(anyString(), anyString(), anyInt()))
                .thenReturn(okJson("""
                        {"columns":[{"columnName":"NOT_A_REAL_COLUMN","description":"x","businessMeaning":"y","role":"metric","aggregationMethod":"SUM"}]}
                        """));

        CatalogueResult result = warehouseResult("WH", "PUBLIC", "T1", col("REAL_COL", "NUMBER"));
        enrichmentService.enrich(result);

        assertTrue(enrichmentService.getLastBatchDiagnostics().stream()
                .anyMatch(d -> "FAILED_MAPPING".equals(d.status)));
        ColumnInfo column = result.getTables().getFirst().getColumn("REAL_COL");
        assertTrue(column.getDescription() == null || column.getDescription().isBlank());
        assertTrue(column.getBusinessMeaning() == null || column.getBusinessMeaning().isBlank());
    }

    private static void assertSemanticParity(CatalogueResult result, String columnName) {
        TableInfo table = result.getTables().getFirst();
        ColumnInfo column = table.getColumn(columnName);
        assertNotNull(table.getDescription());
        assertTrue(table.getDescription().contains("activity"));
        assertEquals("Unique event identifier.", column.getDescription());
        assertTrue(column.getBusinessMeaning().length() >= 80);
        assertEquals("identifier", column.getRole());
        assertEquals("COUNT_DISTINCT", column.getAggregationMethod());
        assertEquals("count", column.getDisplayUnit());
        assertEquals(1.0, column.getScaleToCanonical());
        assertEquals("events", column.getGrainLabel());
        assertEquals(0.9, column.getConfidence());
    }

    private static CatalogueResult warehouseResult(
            String database, String schema, String tableName, ColumnInfo... columns) {
        CatalogueResult result = new CatalogueResult(database, schema);
        TableInfo table = new TableInfo(tableName, schema);
        for (ColumnInfo column : columns) {
            table.addColumn(column);
        }
        result.addTable(table);
        return result;
    }

    private static ColumnInfo col(String name, String type) {
        return new ColumnInfo(name, type, true);
    }

    private static ChatCompletionResult okJson(String json) {
        return new ChatCompletionResult(
                true, 200, "gpt-4o-mini", 4096, json, "stop", 10, 20, 30, json, null);
    }
}
