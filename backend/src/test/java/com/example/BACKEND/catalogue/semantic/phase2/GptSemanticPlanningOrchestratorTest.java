package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModelAdapter;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryValidator;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalSqlRenderer;
import com.example.BACKEND.catalogue.semantic.phase2.completion.ContributionCompleter;
import com.example.BACKEND.catalogue.semantic.phase2.completion.SemanticPlanCompleter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GptSemanticPlanningOrchestratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void planProducesCanonicalSqlFromMockGpt(@TempDir Path tempDir) throws Exception {
        var bundle = MetricResolutionTestSupport.oilBundle();
        String mockJson = """
                {
                  "intent": "RANKING",
                  "metric": "total_revenue",
                  "secondary_metric": null,
                  "dimensions": ["region"],
                  "filters": [],
                  "aggregations": {"primary": "SUM", "secondary": null},
                  "ordering": {"column": "total_revenue", "direction": "DESC"},
                  "limit": 5,
                  "relationship_variable": null,
                  "time_grain": null,
                  "confidence": 0.93,
                  "reasoning": "Top regions by revenue",
                  "alternatives": []
                }
                """;

        GptStructuredCompletionClient mockClient = mock(GptStructuredCompletionClient.class);
        when(mockClient.completeStructured(anyString(), anyString(), any())).thenReturn(mockJson);

        CatalogueApprovalService catalogueService = mock(CatalogueApprovalService.class);
        when(catalogueService.getApprovedSnapshot(anyString())).thenThrow(new RuntimeException("no snapshot"));

        SemanticPlanningProperties props = new SemanticPlanningProperties();
        props.setShadowLogPath(tempDir.resolve("shadow.log").toString());

        GptStructuredSemanticPlanner planner = new GptStructuredSemanticPlanner(mockClient, MAPPER);
        SemanticPlanValidator validator = new SemanticPlanValidator(props);
        SemanticPlanToAnalysisPlanAdapter adapter = new SemanticPlanToAnalysisPlanAdapter();
        DeterministicAnalyticalQueryPlanner sqlPlanner = SqlTemplateTestHarness.create().planner;

        GptSemanticPlanningOrchestrator orchestrator = new GptSemanticPlanningOrchestrator(
                props, planner, validator, adapter, sqlPlanner,
                null, catalogueService,
                new GptSemanticShadowLogger(props, MAPPER),
                new SemanticShadowComparisonFactory(),
                new CanonicalQueryModelAdapter(),
                new CanonicalQueryValidator(props),
                new CanonicalSqlRenderer(),
                new SemanticPlanCompleter(List.of(new ContributionCompleter())),
                MAPPER);

        var outcome = orchestrator.plan("Top regions by revenue", "tenant-1", bundle);

        assertTrue(outcome.validation().valid());
        assertTrue(outcome.canonicalExecutable());
        assertTrue(outcome.analysisPlan().executable());
        assertFalse(outcome.querySpecs().isEmpty());
        assertEquals("canonical__sql", outcome.querySpecs().get(0).key());
        String sql = outcome.querySpecs().get(0).sql().toUpperCase();
        assertTrue(sql.contains("SUM(TOTAL_REVENUE)"));
        assertTrue(sql.contains("GROUP BY REGION"));
        assertFalse(sql.contains("SHARE_PCT"));
        assertNotNull(outcome.canonicalQueryModel());
        assertEquals("total_revenue", outcome.canonicalQueryModel().measure().column());
        assertTrue(outcome.canonicalValidation().valid());
    }

    @Test
    void completesMissingContributionDenominatorBeforeCqm() throws Exception {
        String catalogueJson = """
                {
                  "tables": [{
                    "tableSchema": "nyc_taxi",
                    "tableName": "nyc_taxi",
                    "columns": [
                      {"columnName":"total_amount","role":"metric","dataType":"FLOAT",
                       "description":"Total trip charge including fare, tips, tolls, and surcharges",
                       "aggregationMethod":"SUM"},
                      {"columnName":"Airport_fee","role":"metric","dataType":"FLOAT",
                       "description":"Airport pickup or dropoff surcharge amount","aggregationMethod":"SUM"},
                      {"columnName":"airport_flag","role":"dimension","dataType":"BOOLEAN",
                       "description":"Whether trip involved airport pickup or dropoff"}
                    ]
                  }]
                }
                """;
        String mockJson = """
                {
                  "intent": "CONTRIBUTION",
                  "metric": "Airport_fee",
                  "secondary_metric": null,
                  "dimensions": [],
                  "filters": [],
                  "aggregations": {"primary": "SUM", "secondary": null},
                  "ordering": null,
                  "limit": null,
                  "relationship_variable": null,
                  "time_grain": null,
                  "confidence": 0.91,
                  "reasoning": "airport fee contribution",
                  "alternatives": []
                }
                """;

        var bundle = SemanticPlanCompleterTestSupport.nycTaxiBundle();
        GptStructuredCompletionClient mockClient = mock(GptStructuredCompletionClient.class);
        when(mockClient.completeStructured(anyString(), anyString(), any())).thenReturn(mockJson);

        CatalogueApprovalService catalogueService = mock(CatalogueApprovalService.class);
        when(catalogueService.getApprovedSnapshot(anyString())).thenReturn(catalogueJson);

        SemanticPlanningProperties props = new SemanticPlanningProperties();
        GptStructuredSemanticPlanner planner = new GptStructuredSemanticPlanner(mockClient, MAPPER);
        SemanticPlanValidator validator = new SemanticPlanValidator(props);
        DeterministicAnalyticalQueryPlanner sqlPlanner = SqlTemplateTestHarness.create().planner;

        GptSemanticPlanningOrchestrator orchestrator = new GptSemanticPlanningOrchestrator(
                props, planner, validator, new SemanticPlanToAnalysisPlanAdapter(), sqlPlanner,
                null, catalogueService,
                new GptSemanticShadowLogger(props, MAPPER),
                new SemanticShadowComparisonFactory(),
                new CanonicalQueryModelAdapter(),
                new CanonicalQueryValidator(props),
                new CanonicalSqlRenderer(),
                new SemanticPlanCompleter(List.of(new ContributionCompleter())),
                MAPPER);

        var outcome = orchestrator.plan("How do airport rides contribute to revenue?", "tenant-1", bundle);

        assertTrue(outcome.validation().valid());
        assertEquals("total_amount", outcome.semanticPlan().secondaryMetric());
        assertNotNull(outcome.canonicalQueryModel().ratio());
        assertEquals("total_amount", outcome.canonicalQueryModel().ratio().denominator().column());
        assertTrue(outcome.canonicalExecutable());
    }

    @Test
    void shadowCompareWritesLog(@TempDir Path tempDir) throws Exception {
        var bundle = MetricResolutionTestSupport.oilBundle();
        String mockJson = """
                {
                  "intent": "RELATIONSHIP",
                  "metric": "profit_margin",
                  "secondary_metric": null,
                  "dimensions": [],
                  "filters": [],
                  "aggregations": {"primary": "AVG", "secondary": null},
                  "ordering": null,
                  "limit": null,
                  "relationship_variable": "downtime_hours",
                  "time_grain": null,
                  "confidence": 0.87,
                  "reasoning": "correlation",
                  "alternatives": []
                }
                """;

        GptStructuredCompletionClient mockClient = mock(GptStructuredCompletionClient.class);
        when(mockClient.completeStructured(anyString(), anyString(), any())).thenReturn(mockJson);

        CatalogueApprovalService catalogueService = mock(CatalogueApprovalService.class);
        when(catalogueService.getApprovedSnapshot(anyString())).thenThrow(new RuntimeException("no snapshot"));

        SemanticPlanningProperties props = new SemanticPlanningProperties();
        props.setMode(SemanticPlanningProperties.Mode.shadow);
        props.setShadowExecuteGptSql(false);
        Path logPath = tempDir.resolve("phase2-shadow.log");
        props.setShadowLogPath(logPath.toString());

        GptStructuredSemanticPlanner planner = new GptStructuredSemanticPlanner(mockClient, MAPPER);
        SemanticPlanValidator validator = new SemanticPlanValidator(props);
        SemanticPlanToAnalysisPlanAdapter adapter = new SemanticPlanToAnalysisPlanAdapter();
        DeterministicAnalyticalQueryPlanner sqlPlanner = SqlTemplateTestHarness.create().planner;

        GptSemanticPlanningOrchestrator orchestrator = new GptSemanticPlanningOrchestrator(
                props, planner, validator, adapter, sqlPlanner,
                null, catalogueService,
                new GptSemanticShadowLogger(props, MAPPER),
                new SemanticShadowComparisonFactory(),
                new CanonicalQueryModelAdapter(),
                new CanonicalQueryValidator(props),
                new CanonicalSqlRenderer(),
                new SemanticPlanCompleter(List.of(new ContributionCompleter())),
                MAPPER);

        AnalysisPlan legacyPlan = AnalysisPlan.blocked("q", "legacy");
        List<QuerySpec> legacySpecs = List.of(new QuerySpec("legacy", "SELECT 1", Map.of()));
        List<QueryResult> legacyResults = List.of(new QueryResult("legacy", List.of(Map.of("x", 1)), 5));

        var comparison = orchestrator.shadowCompare(
                UUID.randomUUID(), "How does downtime affect profit?",
                "tenant-1", bundle, legacyPlan, legacySpecs, legacyResults);

        assertNotNull(comparison);
        assertEquals("shadow", comparison.plannerMode());
        assertEquals("legacy", comparison.servedPath());

        assertTrue(java.nio.file.Files.exists(logPath));
        String content = java.nio.file.Files.readString(logPath);
        assertTrue(content.contains("legacyAnalysisPlan"));
        assertTrue(content.contains("gptStructuredPlan"));
        assertTrue(content.contains("confidence"));
    }
}
