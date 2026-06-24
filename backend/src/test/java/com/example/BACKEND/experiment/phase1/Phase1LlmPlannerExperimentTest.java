package com.example.BACKEND.experiment.phase1;

import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.experiment.phase1.Phase1DatasetRegistry.DatasetDef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Phase-1 experiment wiring (no live OpenAI call).
 */
class Phase1LlmPlannerExperimentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void catalogueFactoryHasNoAliases() {
        DatasetDef ds = Phase1DatasetRegistry.get("facility_operations");
        var cat = Phase1CatalogueFactory.catalogueFrom(
                "facility_operations", ds.bundle(), Phase1CataloguePayloadMode.WITH_DESCRIPTIONS);
        assertTrue(cat.metricColumns().contains("unit_cost"));
        assertTrue(cat.dimensionColumns().contains("line_code"));
        assertEquals("facility_operations", cat.tableRef());
    }

    @Test
    void plannerProducesQuerySpecFromMockLlm() throws Exception {
        DatasetDef ds = Phase1DatasetRegistry.get("subscription_events");
        var bundle = ds.bundle();
        var catalogue = Phase1CatalogueFactory.catalogueFrom(
                "subscription_events", bundle, Phase1CataloguePayloadMode.WITH_DESCRIPTIONS);
        var schema = Phase1CatalogueFactory.schemaFrom(bundle);

        String mockJson = """
                {
                  "intent": "CONTRIBUTION",
                  "metric": "payment_total",
                  "aggregation": "SUM",
                  "dimensions": ["billing_region"],
                  "filters": [],
                  "ordering": {"column": "payment_total", "direction": "DESC"},
                  "limit": null,
                  "confidence": 0.95,
                  "reasoning": "Total payments grouped by region",
                  "alternatives": []
                }
                """;

        Phase1LlmClient mock = (sys, user, schemaNode) -> mockJson;
        DeterministicAnalyticalQueryPlanner sqlPlanner = SqlTemplateTestHarness.create().planner;
        Phase1LlmPlannerExperiment exp = new Phase1LlmPlannerExperiment(mock, sqlPlanner, MAPPER);

        var input = new Phase1PlannerInput(
                "Total payment amount by billing region", catalogue, schema);
        Phase1PlannerOutput out = exp.plan(input, bundle);

        assertEquals("payment_total", out.metric());
        assertEquals("billing_region", out.dimensions().get(0));
        assertFalse(out.querySpecs().isEmpty());
        assertTrue(out.querySpecs().get(0).sql().toUpperCase().contains("PAYMENT_TOTAL"));
        assertTrue(out.querySpecs().get(0).sql().toUpperCase().contains("BILLING_REGION"));
    }

    @Test
    void rejectsUnknownColumnsFromLlm() throws Exception {
        DatasetDef ds = Phase1DatasetRegistry.get("orders");
        var bundle = ds.bundle();
        var catalogue = Phase1CatalogueFactory.catalogueFrom(
                "orders", bundle, Phase1CataloguePayloadMode.WITH_DESCRIPTIONS);
        var schema = Phase1CatalogueFactory.schemaFrom(bundle);

        String mockJson = """
                {
                  "intent": "RANKING",
                  "metric": "phantom_revenue",
                  "aggregation": "SUM",
                  "dimensions": ["fake_region"],
                  "filters": [{"column":"ghost","operator":"=","value":"x"}],
                  "ordering": null,
                  "limit": 10,
                  "confidence": 0.9,
                  "reasoning": "test",
                  "alternatives": []
                }
                """;

        Phase1LlmClient mock = (s, u, sch) -> mockJson;
        var exp = new Phase1LlmPlannerExperiment(mock, SqlTemplateTestHarness.create().planner, MAPPER);
        var out = exp.plan(new Phase1PlannerInput("Top regions by value", catalogue, schema), bundle);
        assertTrue(out.metric() == null || out.querySpecs().isEmpty());
        assertTrue(out.filters().isEmpty());
    }

    @Test
    void lowConfidenceReturnsAlternates() throws Exception {
        DatasetDef ds = Phase1DatasetRegistry.get("transactions");
        var bundle = ds.bundle();
        var catalogue = Phase1CatalogueFactory.catalogueFrom(
                "transactions", bundle, Phase1CataloguePayloadMode.WITH_DESCRIPTIONS);
        var schema = Phase1CatalogueFactory.schemaFrom(bundle);

        String mockJson = """
                {
                  "intent": "CONTRIBUTION",
                  "metric": "amount",
                  "aggregation": "SUM",
                  "dimensions": ["category"],
                  "filters": [],
                  "ordering": null,
                  "limit": null,
                  "confidence": 0.55,
                  "reasoning": "uncertain",
                  "alternatives": [
                    {
                      "intent": "CONTRIBUTION",
                      "metric": "quantity",
                      "aggregation": "SUM",
                      "dimensions": ["channel"],
                      "filters": [],
                      "ordering": null,
                      "limit": null,
                      "confidence": 0.5,
                      "reasoning": "alt"
                    }
                  ]
                }
                """;

        Phase1LlmClient mock = (s, u, sch) -> mockJson;
        var exp = new Phase1LlmPlannerExperiment(mock, SqlTemplateTestHarness.create().planner, MAPPER);
        var out = exp.plan(new Phase1PlannerInput("Spend by category or channel?", catalogue, schema), bundle);
        assertEquals(1, out.alternates().size());
        assertEquals("quantity", out.alternates().get(0).metric());
    }
}
