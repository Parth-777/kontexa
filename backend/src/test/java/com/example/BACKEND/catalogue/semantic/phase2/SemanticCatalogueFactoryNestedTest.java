package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ObjectiveDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticCatalogueFactoryNestedTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void loadsNestedLeafPathsFromSnapshotJson() throws Exception {
        String json = """
                {
                  "tables": [{
                    "tableName": "ga_sessions_20160801",
                    "tableSchema": "google_analytics_sample",
                    "columns": [
                      {"columnName":"trafficSource","role":"container","dataType":"RECORD","container":true},
                      {"columnName":"trafficSource.source","role":"dimension","dataType":"STRING","parentPath":"trafficSource"},
                      {"columnName":"trafficSource.medium","role":"dimension","dataType":"STRING","parentPath":"trafficSource"},
                      {"columnName":"visitId","role":"metric","dataType":"INTEGER","aggregationMethod":"COUNT"}
                    ]
                  }]
                }
                """;
        RegistryResolutionBundle bundle = bundle("ga_sessions_20160801");
        ApprovedCatalogueSnapshot catalogue = SemanticCatalogueFactory.catalogueFrom(
                mapper.readTree(json), bundle);

        assertTrue(catalogue.hasColumn("trafficSource.source"));
        assertTrue(catalogue.isContainer("trafficSource"));
        assertFalse(catalogue.isBindableLeaf("trafficSource"));
        assertTrue(catalogue.dimensionColumns().contains("trafficSource.source"));
        assertFalse(catalogue.dimensionColumns().contains("trafficSource"));
    }

    @Test
    void resolveFieldPathPreservesNestedPathsButStripsTablePrefix() {
        assertEquals("trafficSource.source",
                SemanticCatalogueFactory.resolveFieldPath("ga_sessions.trafficSource.source", "ga_sessions"));
        assertEquals("tip_amount",
                SemanticCatalogueFactory.resolveFieldPath("yellow_taxi_trips.tip_amount", "yellow_taxi_trips"));
        assertEquals("trafficSource.source",
                SemanticCatalogueFactory.resolveFieldPath("trafficSource.source", "ga_sessions"));
    }

    @Test
    void schemaFromUsesCatalogueLeafKeysNotSqlExpressionTemplates() {
        RegistryResolutionBundle bundle = new RegistryResolutionBundle(
                List.of(new EntityDescriptor("ga_sessions_20160801", "ga_sessions_20160801", List.of(), List.of())),
                List.of(new MetricDescriptor(
                        "ga_sessions_20160801.totals.visits",
                        "SUM(ga_sessions_20160801.totals.visits)",
                        "INTEGER",
                        "SUM",
                        null)),
                List.of(new DimensionDescriptor(
                        "ga_sessions_20160801.trafficSource.source",
                        "ga_sessions_20160801.trafficSource.source",
                        "CATEGORICAL")),
                new ObjectiveDescriptor("RANKING", "RANKING", List.of()));

        SchemaSnapshot schema = SemanticCatalogueFactory.schemaFrom(bundle);
        List<String> names = schema.columns().stream().map(SchemaSnapshot.SchemaColumn::name).toList();

        assertTrue(names.contains("totals.visits"));
        assertTrue(names.contains("trafficSource.source"));
        assertFalse(names.stream().anyMatch(n -> n.toUpperCase().startsWith("SUM(")));
    }

    @Test
    void mismatchedSnapshotDoesNotMixOrdersIdentityWithNycColumns() throws Exception {
        String nycJson = """
                {
                  "tables": [{
                    "tableName": "nyc_taxi",
                    "tableSchema": "nyc",
                    "columns": [
                      {"columnName":"RatecodeID","role":"metric","dataType":"INTEGER"}
                    ]
                  }]
                }
                """;
        RegistryResolutionBundle ordersBundle = new RegistryResolutionBundle(
                List.of(new EntityDescriptor("ORDERS", "ORDERS", List.of("ORDER_ID"), List.of())),
                List.of(new MetricDescriptor("ORDERS.ORDER_ID", "COUNT(ORDERS.ORDER_ID)", "INTEGER", "COUNT", null)),
                List.of(),
                new ObjectiveDescriptor("RANKING", "RANKING", List.of()));

        ApprovedCatalogueSnapshot catalogue = SemanticCatalogueFactory.catalogueFrom(
                mapper.readTree(nycJson), ordersBundle);

        String cols = catalogue.columns().stream()
                .map(ApprovedCatalogueSnapshot.CatalogueColumn::columnName)
                .toList()
                .toString();
        assertFalse(catalogue.tableRef().equalsIgnoreCase("ORDERS")
                && cols.contains("RatecodeID"),
                "must not mix ORDERS identity with NYC Taxi columns");
        assertEquals("ORDERS", catalogue.tableRef());
        assertFalse(cols.contains("RatecodeID"));
        assertTrue(catalogue.columns().stream()
                .anyMatch(c -> "ORDER_ID".equalsIgnoreCase(c.columnName())));
    }

    @Test
    void flatCatalogueBackwardCompatible() throws Exception {
        String json = """
                {
                  "tables": [{
                    "tableName": "yellow_taxi_trips",
                    "tableSchema": "public",
                    "columns": [
                      {"columnName":"tip_amount","role":"metric","dataType":"FLOAT","aggregationMethod":"AVG"},
                      {"columnName":"payment_type","role":"dimension","dataType":"INTEGER"}
                    ]
                  }]
                }
                """;
        ApprovedCatalogueSnapshot catalogue = SemanticCatalogueFactory.catalogueFrom(
                mapper.readTree(json), bundle("yellow_taxi_trips"));

        assertEquals(List.of("payment_type"), catalogue.dimensionColumns());
        assertEquals(List.of("tip_amount"), catalogue.metricColumns());
    }

    private static RegistryResolutionBundle bundle(String tableRef) {
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor(tableRef, tableRef, List.of(), List.of())),
                List.of(new MetricDescriptor("m1", tableRef + ".visitId", "INTEGER", "COUNT", null)),
                List.of(new DimensionDescriptor(tableRef + ".trafficSource.source",
                        tableRef + ".trafficSource.source", "CATEGORICAL")),
                new ObjectiveDescriptor("RANKING", "RANKING", List.of()));
    }
}
