package com.example.BACKEND.catalogue.query;

import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * General-purpose data chatbot: every question goes to OpenAI with the full
 * database schema. The model can run multiple SQL queries, see the results,
 * refine, and then answer — independent of insight cards.
 */
@Service
public class GeneralDataChatService {

    private static final int MAX_QUERY_STEPS = 4;

    private final OpenAiClient               openAiClient;
    private final CatalogueApprovalService   approvalService;
    private final CataloguePromptBuilder     promptBuilder;
    private final CatalogueQueryService      queryService;
    private final TenantCloudConnectionService cloudConnectionService;
    private final ObjectMapper               objectMapper;

    public GeneralDataChatService(
            OpenAiClient openAiClient,
            CatalogueApprovalService approvalService,
            CataloguePromptBuilder promptBuilder,
            CatalogueQueryService queryService,
            TenantCloudConnectionService cloudConnectionService,
            ObjectMapper objectMapper
    ) {
        this.openAiClient            = openAiClient;
        this.approvalService         = approvalService;
        this.promptBuilder           = promptBuilder;
        this.queryService            = queryService;
        this.cloudConnectionService  = cloudConnectionService;
        this.objectMapper            = objectMapper;
    }

    public ChatOrchestratorService.ChatResponse chat(String question, String clientId) {
        try {
            String snapshotJson = approvalService.getApprovedSnapshot(clientId);
            JsonNode fullNode   = objectMapper.readTree(snapshotJson);

            String dialect = resolveDialect(clientId);
            String systemPrompt = buildAgentSystemPrompt(fullNode, dialect);
            String history = "";

            String lastSql = null;
            List<Map<String, Object>> lastRows = List.of();

            for (int step = 0; step < MAX_QUERY_STEPS; step++) {
                String userPrompt = buildStepPrompt(question, history, step);
                String llmJson    = openAiClient.chat(systemPrompt, userPrompt);
                JsonNode node     = objectMapper.readTree(cleanJson(llmJson));

                String action = node.path("action").asText("answer").toLowerCase();

                if ("answer".equals(action)) {
                    String answer = node.path("answer").asText("").trim();
                    List<String> followUps = parseStringList(node.path("followUpSuggestions"));

                    String sqlToShow = node.path("sql").asText("").trim();
                    if (sqlToShow.isBlank()) sqlToShow = lastSql;

                    boolean includeTable = node.path("includeTable").asBoolean(!lastRows.isEmpty());

                    if (includeTable && !lastRows.isEmpty()) {
                        return new ChatOrchestratorService.ChatResponse(
                                "mixed", answer, sqlToShow, lastRows, lastRows.size(), followUps);
                    }
                    return new ChatOrchestratorService.ChatResponse(
                            "reasoning", answer, sqlToShow.isBlank() ? null : sqlToShow,
                            List.of(), 0, followUps);
                }

                if ("query".equals(action)) {
                    String sql = sanitizeSql(node.path("sql").asText(""));
                    if (sql.isBlank()) {
                        history += "\n[System: query action missing SQL — try again or answer]\n";
                        continue;
                    }

                    try {
                        CatalogueQueryService.QueryResult result =
                                queryService.executeSqlForChat(clientId, sql, question);
                        lastSql  = result.getGeneratedSql();
                        lastRows = result.getRows();

                        history += "\n\n── Query " + (step + 1) + " ──\n";
                        history += "Purpose: " + node.path("purpose").asText("") + "\n";
                        history += "SQL: " + lastSql + "\n";
                        history += "Result: " + result.getRowCount() + " rows\n";
                        history += formatRows(result.getRows());

                        if (result.getRowCount() == 0) {
                            history += "\n[0 rows — try a different query: other columns, remove filters, or GROUP BY year from data]\n";
                        }
                    } catch (Exception e) {
                        history += "\n\n── Query " + (step + 1) + " FAILED ──\n";
                        history += "SQL: " + sql + "\n";
                        history += "Error: " + e.getMessage() + "\n";
                    }
                    continue;
                }

                history += "\n[Unknown action — use query or answer]\n";
            }

            // Max steps reached — force final answer from whatever data we collected
            return forceFinalAnswer(question, history, lastSql, lastRows, systemPrompt);

        } catch (Exception e) {
            return ChatOrchestratorService.ChatResponse.error(
                    "Chat failed: " + e.getMessage());
        }
    }

    private ChatOrchestratorService.ChatResponse forceFinalAnswer(
            String question, String history, String lastSql,
            List<Map<String, Object>> lastRows, String systemPrompt
    ) {
        String prompt =
                "Question: \"" + question + "\"\n\n" +
                "Query history and results:\n" + history + "\n\n" +
                "You must now give the final answer. Respond with JSON:\n" +
                "{\"action\":\"answer\",\"answer\":\"...\",\"followUpSuggestions\":[],\"includeTable\":true}";

        try {
            String llmJson = openAiClient.chat(systemPrompt, prompt);
            JsonNode node  = objectMapper.readTree(cleanJson(llmJson));
            String answer  = node.path("answer").asText("").trim();
            List<String> followUps = parseStringList(node.path("followUpSuggestions"));

            if (!lastRows.isEmpty()) {
                return new ChatOrchestratorService.ChatResponse(
                        "mixed", answer, lastSql, lastRows, lastRows.size(), followUps);
            }
            return new ChatOrchestratorService.ChatResponse(
                    "reasoning", answer, lastSql, List.of(), 0, followUps);
        } catch (Exception e) {
            if (!lastRows.isEmpty()) {
                return new ChatOrchestratorService.ChatResponse(
                        "mixed",
                        "Analysis based on " + lastRows.size() + " rows — see table below.",
                        lastSql, lastRows, lastRows.size(), List.of());
            }
            return ChatOrchestratorService.ChatResponse.error(
                    "Could not complete analysis: " + e.getMessage());
        }
    }

    private String buildAgentSystemPrompt(JsonNode catalogueNode, String dialect) {
        String schemaBlock = promptBuilder.buildSystemPromptFromSnapshot(catalogueNode);

        return """
                You are Kontexa's general data analyst chatbot. You answer ANY user question
                by exploring and querying their database. You are NOT limited to pre-generated
                insights — you have full access to run SQL against their tables.

                WORKFLOW (each turn, respond with ONE JSON object):

                1) To fetch data:
                {"action":"query","sql":"SELECT ...","purpose":"brief reason"}

                2) When you have enough data to answer:
                {"action":"answer","answer":"professional 3-6 sentence answer with specific numbers",
                 "followUpSuggestions":["...","...","..."],
                 "includeTable":true,
                 "sql":"optional — the main query to show the user"}

                You may run up to 3 queries before answering. Use query steps to explore:
                - distinct years / periods in date columns
                - aggregates by category
                - trends over time

                CRITICAL SQL RULES:
                """ + dialect + """

                - ONLY SELECT statements. Always LIMIT 1000 unless aggregating all years.
                - Use fully qualified schema.table names exactly as in the schema below.
                - For TRENDS / "over the years" / "past years": NEVER use only CURRENT_DATE or
                  EXTRACT(YEAR FROM CURRENT_DATE()) — that returns ONE year only.
                  Instead: GROUP BY the actual year/date column in the table to get ALL years
                  present in the data. Example:
                  SELECT year_column, SUM(employee_count) AS total FROM schema.table
                  GROUP BY year_column ORDER BY year_column
                - Map user terms to real columns (e.g. "turnover" → closest column in schema).
                - BOOLEAN columns: = TRUE or = FALSE — never LIKE '%yes%'.
                - Use exact sample values from schema for text filters.
                - INTEGER columns: = or BETWEEN, never ILIKE.

                ANSWER RULES:
                - Answer directly from query results. Cite specific numbers.
                - Never say "insights do not include" or "we would need more data" if you have rows.
                - If data shows one year only, run another query to get all years first.
                - Be professional and concise.

                """ + schemaBlock;
    }

    private String buildStepPrompt(String question, String history, int step) {
        if (history.isBlank()) {
            return "User question: \"" + question + "\"\n\n" +
                   "Start by running the SQL query(ies) needed, or answer directly if obvious.";
        }
        return "User question: \"" + question + "\"\n\n" +
               "Previous queries and results:" + history + "\n\n" +
               (step >= MAX_QUERY_STEPS - 1
                       ? "This is your last step — you MUST respond with action=answer now."
                       : "Run another query if needed, or respond with action=answer.");
    }

    private String resolveDialect(String clientId) {
        String provider = cloudConnectionService.getProvider(clientId);
        if ("bigquery".equals(provider))  return "SQL dialect: BigQuery Standard SQL.";
        if ("snowflake".equals(provider)) return "SQL dialect: Snowflake SQL (ANSI).";
        return "SQL dialect: PostgreSQL.";
    }

    private String formatRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return "(no rows)\n";
        int show = Math.min(rows.size(), 25);
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(" | ", rows.get(0).keySet())).append("\n");
        for (int i = 0; i < show; i++) {
            sb.append(String.join(" | ", rows.get(i).values().stream()
                    .map(v -> v == null ? "null" : v.toString()).toList())).append("\n");
        }
        if (rows.size() > show) sb.append("... ").append(rows.size() - show).append(" more rows\n");
        return sb.toString();
    }

    private String sanitizeSql(String sql) {
        if (sql == null) return "";
        sql = sql.trim();
        if (sql.startsWith("```")) {
            sql = sql.replaceAll("(?is)^```sql\\s*", "").replaceAll("(?is)^```\\s*", "")
                     .replaceAll("```\\s*$", "").trim();
        }
        return sql;
    }

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        raw = raw.trim();
        if (raw.startsWith("```")) {
            raw = raw.replaceAll("(?is)^```json\\s*", "").replaceAll("(?is)^```\\s*", "")
                     .replaceAll("```\\s*$", "").trim();
        }
        return raw;
    }

    private List<String> parseStringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String s = n.asText("").trim();
                if (!s.isBlank()) out.add(s);
            }
        }
        return out;
    }
}
