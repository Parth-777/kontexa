package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModelAdapter;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryValidator;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalSqlRenderer;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Live trace: raw GPT planner JSON through {@link com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel}.
 */
class PlannerPipelineObjectTraceTest {

    private static final String QUESTION = "How do airport rides contribute to revenue?";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void traceAirportContributionPlannerPipeline() throws Exception {
        Properties props = loadApplicationProperties();
        String apiKey = props.getProperty("openai.api.key");
        String model = props.getProperty("openai.model", "gpt-4o-mini");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("SKIP: openai.api.key not configured");
            return;
        }

        RegistryResolutionBundle bundle = nycTaxiBundle();
        JsonNode catalogueJson = nycTaxiCatalogueJson();
        ApprovedCatalogueSnapshot catalogue = SemanticCatalogueFactory.catalogueFrom(catalogueJson, bundle);

        GptStructuredCompletionClient client = new GptStructuredCompletionClient(apiKey, model, MAPPER);
        CatalogueApprovalService catalogueService = mock(CatalogueApprovalService.class);
        when(catalogueService.getApprovedSnapshot(anyString())).thenReturn(catalogueJson.toString());

        SemanticPlanningProperties planningProps = new SemanticPlanningProperties();
        GptStructuredSemanticPlanner planner = new GptStructuredSemanticPlanner(client, MAPPER);
        SemanticPlanValidator validator = new SemanticPlanValidator(planningProps);
        DeterministicAnalyticalQueryPlanner sqlPlanner = SqlTemplateTestHarness.create().planner;

        GptSemanticPlanningOrchestrator orchestrator = new GptSemanticPlanningOrchestrator(
                planningProps, planner, validator,
                new SemanticPlanToAnalysisPlanAdapter(), sqlPlanner,
                null, catalogueService,
                new GptSemanticShadowLogger(planningProps, MAPPER),
                new SemanticShadowComparisonFactory(),
                new CanonicalQueryModelAdapter(),
                new CanonicalQueryValidator(planningProps),
                new CanonicalSqlRenderer(),
                MAPPER);

        System.out.println("=== TRACE INPUT ===");
        System.out.println("question: " + QUESTION);
        System.out.println("table: " + catalogue.tableRef());
        System.out.println("catalogue_columns: " + catalogue.columns().stream()
                .map(ApprovedCatalogueSnapshot.CatalogueColumn::columnName)
                .toList());

        var outcome = orchestrator.plan(QUESTION, "nyc-taxi-trace", bundle);

        Path out = Path.of("target/planner-pipeline-trace-airport.json");
        var report = MAPPER.createObjectNode();
        report.put("question", QUESTION);
        report.put("note", "See test stdout and logs tagged [planner-pipeline-trace] for raw GPT JSON");
        report.set("structuredSemanticPlan", MAPPER.valueToTree(outcome.semanticPlan()));
        report.set("validation", MAPPER.valueToTree(outcome.validation()));
        report.set("canonicalQueryModel", MAPPER.valueToTree(outcome.canonicalQueryModel()));
        report.set("canonicalValidation", MAPPER.valueToTree(outcome.canonicalValidation()));
        if (!outcome.querySpecs().isEmpty()) {
            report.put("canonicalSql", outcome.querySpecs().getFirst().sql());
        }
        Files.createDirectories(out.getParent());
        Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));

        System.out.println("=== STAGE: StructuredSemanticPlan (post-parsePlan) ===");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(outcome.semanticPlan()));
        System.out.println("=== STAGE: CanonicalQueryModel (post-adapt) ===");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(outcome.canonicalQueryModel()));
        System.out.println("Wrote " + out.toAbsolutePath());

        assertNotNull(outcome.semanticPlan());
        assertNotNull(outcome.canonicalQueryModel());
    }

    private static Properties loadApplicationProperties() throws Exception {
        Properties props = new Properties();
        try (InputStream in = PlannerPipelineObjectTraceTest.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(in);
            }
        }
        Path local = Path.of("src/main/resources/application.properties");
        if (Files.exists(local)) {
            try (InputStream in = Files.newInputStream(local)) {
                props.load(in);
            }
        }
        return props;
    }

    private static RegistryResolutionBundle nycTaxiBundle() {
        String table = "yellow_taxi_trips";
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor("taxi", table, List.of("trip_id"), List.of("nyc_taxi"))),
                List.of(
                        new MetricDescriptor(table + ".total_amount", "total_amount", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".fare_amount", "fare_amount", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".Airport_fee", "Airport_fee", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".tip_amount", "tip_amount", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".trip_distance", "trip_distance", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".VendorID", "VendorID", "INTEGER", "COUNT", null)
                ),
                List.of(
                        new DimensionDescriptor(table + ".airport_flag", "airport_flag", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".store_and_fwd_flag", "store_and_fwd_flag", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".pickup_zone", "pickup_zone", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".payment_type", "payment_type", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".VendorID", "VendorID", "CATEGORICAL")
                ),
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
                      {"columnName":"trip_distance","role":"metric","dataType":"FLOAT",
                       "description":"Trip distance in miles","aggregationMethod":"SUM","sampleValues":"[]"},
                      {"columnName":"VendorID","role":"dimension","dataType":"INTEGER",
                       "description":"Taxi vendor or technology provider identifier","sampleValues":"[\\"1\\",\\"2\\"]"},
                      {"columnName":"airport_flag","role":"dimension","dataType":"BOOLEAN",
                       "description":"Whether trip involved airport pickup or dropoff","sampleValues":"[\\"true\\",\\"false\\"]"},
                      {"columnName":"store_and_fwd_flag","role":"dimension","dataType":"BOOLEAN",
                       "description":"Store and forward flag","sampleValues":"[\\"Y\\",\\"N\\"]"},
                      {"columnName":"pickup_zone","role":"dimension","dataType":"VARCHAR",
                       "description":"Pickup taxi zone","sampleValues":"[]"},
                      {"columnName":"payment_type","role":"dimension","dataType":"VARCHAR",
                       "description":"Payment method","sampleValues":"[]"}
                    ]
                  }]
                }
                """;
        return MAPPER.readTree(json);
    }
}
