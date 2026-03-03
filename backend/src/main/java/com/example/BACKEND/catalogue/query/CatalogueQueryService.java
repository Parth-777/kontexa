package com.example.BACKEND.catalogue.query;

import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CatalogueQueryService
 *
 * The generic NLP → SQL engine.
 *
 * Flow:
 *   1. Load the approved catalogue SNAPSHOT for the given clientId (one DB read)
 *   2. Build a schema-aware prompt from the snapshot JSON
 *   3. Call OpenAI → receive {"sql": "SELECT ..."}
 *   4. Execute SQL via JdbcTemplate
 *   5. Return question + generated SQL + result rows
 *
 * Uses the snapshot table instead of joining 3 tables — much faster at query time.
 */
@Service
public class CatalogueQueryService {

    private final CatalogueApprovalService approvalService;
    private final CataloguePromptBuilder promptBuilder;
    private final OpenAiClient openAiClient;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CatalogueQueryService(CatalogueApprovalService approvalService,
                                  CataloguePromptBuilder promptBuilder,
                                  OpenAiClient openAiClient,
                                  JdbcTemplate jdbcTemplate,
                                  ObjectMapper objectMapper) {
        this.approvalService = approvalService;
        this.promptBuilder   = promptBuilder;
        this.openAiClient    = openAiClient;
        this.jdbcTemplate    = jdbcTemplate;
        this.objectMapper    = objectMapper;
    }

    /**
     * Answer a plain-English question using the approved catalogue snapshot.
     *
     * @param clientId  the client identifier (must have an APPROVED catalogue)
     * @param question  plain-English question e.g. "How many movies were added in 2023?"
     * @return          QueryResult with the generated SQL and result rows
     */
    public QueryResult ask(String clientId, String question) {

        // Step 1: Load snapshot (one DB read — the full catalogue as JSON)
        System.out.println("[CatalogueQuery] Loading snapshot for client: " + clientId);
        String snapshotJson = approvalService.getApprovedSnapshot(clientId);
        JsonNode catalogueNode = parseSnapshot(snapshotJson);
        System.out.println("[CatalogueQuery] Snapshot loaded");

        // Step 2: Build prompts directly from the snapshot JSON
        String systemPrompt = promptBuilder.buildSystemPromptFromSnapshot(catalogueNode);
        String userPrompt   = promptBuilder.buildUserPrompt(question);

        System.out.println("[CatalogueQuery] Sending question to LLM: " + question);

        // Step 3: Call LLM → get SQL
        String rawSql = callLlmForSql(systemPrompt, userPrompt);
        System.out.println("[CatalogueQuery] Generated SQL:\n" + rawSql);

        // Step 4: Execute SQL
        List<Map<String, Object>> rows = executeQuery(rawSql);
        System.out.println("[CatalogueQuery] Query returned " + rows.size() + " rows");

        return new QueryResult(question, rawSql, rows);
    }

    // ── Private helpers ──────────────────────────────────────────────

    private JsonNode parseSnapshot(String snapshotJson) {
        try {
            return objectMapper.readTree(snapshotJson);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse catalogue snapshot JSON: " + e.getMessage(), e);
        }
    }

    private String callLlmForSql(String systemPrompt, String userPrompt) {
        String augmentedSystem = systemPrompt
                + "\nIMPORTANT: You must return your answer as a JSON object in this exact format:\n"
                + "{\"sql\": \"<your PostgreSQL SELECT query here>\"}\n"
                + "Do not include any other keys. Put the full SQL as the value of the 'sql' key.";

        String jsonResponse = openAiClient.chat(augmentedSystem, userPrompt);

        try {
            JsonNode node = objectMapper.readTree(jsonResponse);
            String sql = node.path("sql").asText();
            if (sql == null || sql.isBlank()) {
                throw new RuntimeException("LLM returned empty sql field. Full response: " + jsonResponse);
            }
            return sql.trim();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse SQL from LLM response: " + jsonResponse, e);
        }
    }

    private List<Map<String, Object>> executeQuery(String sql) {
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                int columnCount = rs.getMetaData().getColumnCount();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                return row;
            });
        } catch (Exception e) {
            throw new RuntimeException("SQL execution failed.\nSQL: " + sql
                    + "\nError: " + e.getMessage(), e);
        }
    }

    // ── Result record ────────────────────────────────────────────────

    public static class QueryResult {
        private final String question;
        private final String generatedSql;
        private final List<Map<String, Object>> rows;
        private final int rowCount;

        public QueryResult(String question, String generatedSql, List<Map<String, Object>> rows) {
            this.question     = question;
            this.generatedSql = generatedSql;
            this.rows         = rows != null ? rows : new ArrayList<>();
            this.rowCount     = this.rows.size();
        }

        public String getQuestion()                    { return question; }
        public String getGeneratedSql()                { return generatedSql; }
        public List<Map<String, Object>> getRows()     { return rows; }
        public int getRowCount()                       { return rowCount; }
    }
}
