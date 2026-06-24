package com.example.BACKEND.catalogue.charts;

import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.example.BACKEND.catalogue.query.CataloguePromptBuilder;
import com.example.BACKEND.catalogue.query.CatalogueQueryService;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Generates charts from natural-language requests like
 * "generate a bar chart of payment type distribution".
 */
@Service
public class DynamicChartService {

    private static final Pattern CHART_INTENT = Pattern.compile(
            "\\b(generate|create|make|build|show)\\b.*\\b(chart|graph|plot|visuali[sz]e)\\b",
            Pattern.CASE_INSENSITIVE);

    private final OpenAiClient openAiClient;
    private final CatalogueApprovalService approvalService;
    private final CataloguePromptBuilder promptBuilder;
    private final CatalogueQueryService queryService;
    private final DynamicChartMapper chartMapper;
    private final ObjectMapper objectMapper;

    public DynamicChartService(
            OpenAiClient openAiClient,
            CatalogueApprovalService approvalService,
            CataloguePromptBuilder promptBuilder,
            CatalogueQueryService queryService,
            DynamicChartMapper chartMapper,
            ObjectMapper objectMapper
    ) {
        this.openAiClient = openAiClient;
        this.approvalService = approvalService;
        this.promptBuilder = promptBuilder;
        this.queryService = queryService;
        this.chartMapper = chartMapper;
        this.objectMapper = objectMapper;
    }

    public DynamicChartResult generate(String request, String clientId) {
        if (request == null || request.isBlank()) {
            return DynamicChartResult.error("Chart request is required.");
        }
        if (!looksLikeChartRequest(request)) {
            return DynamicChartResult.error(
                    "Please ask for a chart explicitly, e.g. \"Generate a bar chart of revenue by payment type\".");
        }

        try {
            String snapshotJson = approvalService.getApprovedSnapshot(clientId);
            JsonNode catalogueNode = objectMapper.readTree(snapshotJson);
            String schemaPrompt = promptBuilder.buildSystemPromptFromSnapshot(catalogueNode);

            String systemPrompt =
                    schemaPrompt + "\n\n" +
                    "You are a chart planner. The user wants a chart from warehouse data.\n" +
                    "Respond ONLY with JSON (no markdown):\n" +
                    "{\n" +
                    "  \"chartType\": \"BAR|LINE|DONUT\",\n" +
                    "  \"title\": \"short chart title\",\n" +
                    "  \"subtitle\": \"optional context\",\n" +
                    "  \"sql\": \"SELECT ... (aggregated, <= 20 rows)\",\n" +
                    "  \"categoryKey\": \"column for categories (BAR/DONUT)\",\n" +
                    "  \"valueKey\": \"numeric column (BAR/DONUT)\",\n" +
                    "  \"xKey\": \"x axis column (LINE)\",\n" +
                    "  \"yKey\": \"y axis column (LINE)\",\n" +
                    "  \"valueFormat\": \"number|percent|currency\",\n" +
                    "  \"xFormat\": \"date|category\",\n" +
                    "  \"explanation\": \"one sentence for the user\"\n" +
                    "}\n\n" +
                    "Rules:\n" +
                    "- SQL must be a single SELECT, aggregated for charting (GROUP BY), LIMIT 20.\n" +
                    "- Prefer meaningful aliases matching categoryKey/valueKey/xKey/yKey.\n" +
                    "- LINE for trends over time; DONUT/ BAR for composition or rankings.\n" +
                    "- Do not use CURRENT_DATE if data is historical — anchor on MAX(date) from schema.\n";

            String llmJson = openAiClient.chat(systemPrompt, "User chart request: " + request);
            JsonNode plan = objectMapper.readTree(cleanJson(llmJson));

            String sql = plan.path("sql").asText("").trim();
            if (sql.isBlank()) {
                return DynamicChartResult.error("Could not build SQL for this chart request.");
            }

            CatalogueQueryService.QueryResult queryResult =
                    queryService.executeSqlForChat(clientId, sql, request);

            if (queryResult.getRows() == null || queryResult.getRows().isEmpty()) {
                return DynamicChartResult.error(
                        "Query returned no rows. Try a broader chart request or different dimension.");
            }

            ChartSpec.ChartType type = chartMapper.parseType(plan.path("chartType").asText("BAR"));
            ChartSpec chart = chartMapper.toChartSpec(
                    type,
                    plan.path("title").asText("Generated chart"),
                    plan.path("subtitle").asText(null),
                    plan.path("categoryKey").asText(null),
                    plan.path("valueKey").asText(null),
                    plan.path("xKey").asText(null),
                    plan.path("yKey").asText(null),
                    plan.path("valueFormat").asText(null),
                    plan.path("xFormat").asText(null),
                    queryResult.getRows()
            );

            if (chart == null) {
                return DynamicChartResult.error(
                        "Could not map query results to a chart. Try specifying dimension and metric clearly.");
            }

            String explanation = plan.path("explanation").asText(
                    "Generated " + type.name().toLowerCase(Locale.ROOT) + " chart: " + chart.getTitle());

            return new DynamicChartResult(
                    true,
                    explanation,
                    chart,
                    queryResult.getGeneratedSql(),
                    null
            );
        } catch (Exception e) {
            System.out.printf("[Charts] Dynamic generation failed: %s%n", e.getMessage());
            return DynamicChartResult.error("Chart generation failed: " + e.getMessage());
        }
    }

    public boolean looksLikeChartRequest(String request) {
        return CHART_INTENT.matcher(request).find()
                || request.toLowerCase(Locale.ROOT).contains("chart");
    }

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return s.trim();
    }

    public record DynamicChartResult(
            boolean success,
            String answer,
            ChartSpec chart,
            String generatedSql,
            String error
    ) {
        public static DynamicChartResult error(String message) {
            return new DynamicChartResult(false, message, null, null, message);
        }
    }
}
