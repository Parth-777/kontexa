package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Frontend-shaped payload for the six milestone questions using live planner
 * traces + mocked warehouse rows (no auth / tenant required).
 */
class InvestigationMilestoneFrontendPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Milestone plans produce frontend investigation payloads")
    void milestonePlansProduceFrontendPayloads() throws Exception {
        Path traces = Path.of("target/investigation-frontend-milestone-traces.json");
        assertTrue(Files.exists(traces), "Run InvestigationFrontendMilestoneLiveTest first");

        JsonNode root = MAPPER.readTree(traces.toFile());
        assertTrue(root.isArray() && root.size() >= 6);

        InvestigationPlanParser planParser = new InvestigationPlanParser();
        InvestigationObjectiveParser objectiveParser = new InvestigationObjectiveParser();
        EvidenceRequestSqlCompiler compiler = new EvidenceRequestSqlCompiler(new SlicePredicateBuilder());
        EvidencePackAssembler assembler = new EvidencePackAssembler();
        NarrativeRenderer narrativeRenderer = new NarrativeRenderer();

        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode row : root) {
            String question = row.path("question").asText();
            ObjectNode result = MAPPER.createObjectNode();
            result.put("question", question);
            result.put("execution_mode", row.path("execution_mode").asText());

            JsonNode planNode = row.get("investigation_plan");
            if (planNode == null || planNode.isNull()) {
                result.put("ok", false);
                result.put("failure", "planner_emitted_no_investigation_plan");
                out.add(result);
                continue;
            }

            InvestigationPlan plan = planParser.parse(planNode, objectiveParser);
            assertNotNull(plan);
            assertFalse(plan.evidenceRequests().isEmpty());

            EvidenceRequestSqlCompiler.CompiledProgram compiled =
                    compiler.compile(plan, "yellow_taxi_trips");

            ArrayNode sqlNode = result.putArray("generated_sql");
            List<EvidencePack.RequestResult> requestResults = new ArrayList<>();
            for (var cr : compiled.requests()) {
                ObjectNode s = sqlNode.addObject();
                s.put("request_id", cr.request().requestId());
                s.put("kind", cr.request().kind());
                s.put("ok", cr.ok());
                if (!cr.ok()) {
                    s.put("error", cr.error());
                    requestResults.add(EvidencePack.RequestResult.failure(
                            cr.request().requestId(),
                            cr.request().kind(),
                            cr.request().role(),
                            cr.error()));
                    continue;
                }
                s.put("sql", cr.spec().sql());
                List<Map<String, Object>> rows = mockRowsForKind(cr.request().kind());
                s.put("row_count", rows.size());
                requestResults.add(EvidencePack.RequestResult.success(
                        cr.request().requestId(),
                        cr.request().kind(),
                        cr.request().role(),
                        List.of(cr.spec().key()),
                        rows));
            }

            EvidencePack pack = assembler.assemble(question, plan, "yellow_taxi_trips", requestResults);
            NarrativeOutput narrative = narrativeRenderer.render(pack);
            DecisionRunResult decision = narrativeRenderer.toResult("milestone-ui", pack, narrative);

            assertNotNull(decision.routing());
            assertNotNull(decision.routing().investigation());
            assertTrue(decision.routing().investigation().containsKey("evidence_requests"));

            result.put("ok", true);
            result.put("title", decision.insight().title());
            result.put("narrative", decision.insight().narrative());
            result.set("coverage", MAPPER.valueToTree(pack.coverage()));
            result.set("frontend_investigation", MAPPER.valueToTree(decision.routing().investigation()));
            out.add(result);
        }

        Path path = Path.of("target/investigation-milestone-frontend-payloads.json");
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        System.out.println("Wrote " + path.toAbsolutePath());

        long okCount = 0;
        for (JsonNode n : out) {
            if (n.path("ok").asBoolean(false)) {
                okCount++;
            }
        }
        assertTrue(okCount == out.size(), "All milestone questions must produce frontend payloads");
    }

    private static List<Map<String, Object>> mockRowsForKind(String kind) {
        return switch (kind == null ? "" : kind.toLowerCase()) {
            case "correlation" -> List.of(Map.of("correlation", 0.72, "n", 1000));
            case "distribution" -> List.of(
                    Map.of("member", "1.0", "count", 120),
                    Map.of("member", "2.0", "count", 80));
            case "slice_metric" -> List.of(Map.of("value", 2.45));
            case "top_n", "rank", "bottom_n", "share" -> List.of(
                    mapOf("member", "c1", "value", 500.0, "share", 0.25),
                    mapOf("member", "c2", "value", 300.0, "share", 0.15));
            case "concentration" -> List.of(
                    mapOf("member", "c1", "value", 500.0, "share", 0.4, "cumulative_share", 0.4),
                    mapOf("member", "c2", "value", 300.0, "share", 0.24, "cumulative_share", 0.64));
            case "time_series" -> List.of(
                    Map.of("period", "2024-01-01", "value", 1000),
                    Map.of("period", "2024-02-01", "value", 1200));
            case "metric_by_dimension" -> List.of(
                    Map.of("member", "1", "value", 3.1),
                    Map.of("member", "2", "value", 1.8));
            default -> List.of(Map.of("value", 1));
        };
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
