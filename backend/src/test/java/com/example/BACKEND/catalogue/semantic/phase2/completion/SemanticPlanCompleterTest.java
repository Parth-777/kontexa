package com.example.BACKEND.catalogue.semantic.phase2.completion;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticCatalogueFactory;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SemanticPlanCompleterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SemanticPlanCompleter completer;
    private ApprovedCatalogueSnapshot taxiCatalogue;

    @BeforeEach
    void setUp() throws Exception {
        completer = new SemanticPlanCompleter(List.of(new ContributionCompleter()));
        taxiCatalogue = SemanticCatalogueFactory.catalogueFrom(nycTaxiCatalogueJson(), nycTaxiBundle());
    }

    @Test
    void leavesPlanUnchangedWhenDenominatorAlreadyPresent() {
        StructuredSemanticPlan plan = contributionPlan("Airport_fee", "total_amount");

        StructuredSemanticPlan completed = completer.complete(plan, taxiCatalogue);

        assertEquals(plan, completed);
        assertEquals("total_amount", completed.secondaryMetric());
    }

    @Test
    void infersDenominatorFromCatalogueForContribution() {
        StructuredSemanticPlan plan = contributionPlan("Airport_fee", null);

        StructuredSemanticPlan completed = completer.complete(plan, taxiCatalogue);

        assertEquals("total_amount", completed.secondaryMetric());
        assertEquals("SUM", completed.aggregations().secondary());
    }

    @Test
    void leavesPlanUnchangedWhenNoSuitableDenominatorExists() {
        ApprovedCatalogueSnapshot oilCatalogue = SemanticCatalogueFactory.catalogueFrom(
                null,
                com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport.oilBundle());
        StructuredSemanticPlan plan = contributionPlan("maintenance_cost", null);

        StructuredSemanticPlan completed = completer.complete(plan, oilCatalogue);

        assertNull(completed.secondaryMetric());
        assertEquals(plan.metric(), completed.metric());
    }

    @Test
    void doesNotAlterNonContributionPlans() {
        StructuredSemanticPlan plan = new StructuredSemanticPlan(
                "RANKING", "total_amount", null,
                List.of("airport_flag"), List.of(),
                new StructuredSemanticPlan.SemanticAggregations("SUM", null),
                null, null, null, null,
                0.9, "rank", List.of());

        StructuredSemanticPlan completed = completer.complete(plan, taxiCatalogue);

        assertEquals(plan, completed);
    }

    private static StructuredSemanticPlan contributionPlan(String metric, String secondaryMetric) {
        return new StructuredSemanticPlan(
                "CONTRIBUTION",
                metric,
                secondaryMetric,
                List.of(),
                List.of(),
                new StructuredSemanticPlan.SemanticAggregations("SUM", secondaryMetric != null ? "SUM" : null),
                null,
                null,
                null,
                null,
                0.9,
                "contribution analysis",
                List.of());
    }

    private static RegistryResolutionBundle nycTaxiBundle() {
        String table = "yellow_taxi_trips";
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor("taxi", table, List.of("trip_id"), List.of("nyc_taxi"))),
                List.of(
                        new MetricDescriptor(table + ".total_amount", "total_amount", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".fare_amount", "fare_amount", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".Airport_fee", "Airport_fee", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".tip_amount", "tip_amount", "FLOAT", "SUM", null)),
                List.of(new DimensionDescriptor(table + ".airport_flag", "airport_flag", "CATEGORICAL")),
                null);
    }

    private static JsonNode nycTaxiCatalogueJson() throws Exception {
        String json = """
                {
                  "schemaName": "nyc_taxi",
                  "tables": [{
                    "tableSchema": "nyc_taxi",
                    "tableName": "nyc_taxi",
                    "description": "NYC yellow taxi trips",
                    "columns": [
                      {"columnName":"total_amount","role":"metric","dataType":"FLOAT",
                       "description":"Total trip charge including fare, tips, tolls, and surcharges",
                       "aggregationMethod":"SUM","sampleValues":"[]"},
                      {"columnName":"fare_amount","role":"metric","dataType":"FLOAT",
                       "description":"Base fare before tips and surcharges","aggregationMethod":"SUM","sampleValues":"[]"},
                      {"columnName":"Airport_fee","role":"metric","dataType":"FLOAT",
                       "description":"Airport pickup or dropoff surcharge amount","aggregationMethod":"SUM","sampleValues":"[]"},
                      {"columnName":"tip_amount","role":"metric","dataType":"FLOAT",
                       "description":"Tip paid to driver","aggregationMethod":"SUM","sampleValues":"[]"},
                      {"columnName":"airport_flag","role":"dimension","dataType":"BOOLEAN",
                       "description":"Whether trip involved airport pickup or dropoff","sampleValues":"[\\"true\\",\\"false\\"]"}
                    ]
                  }]
                }
                """;
        return MAPPER.readTree(json);
    }
}
