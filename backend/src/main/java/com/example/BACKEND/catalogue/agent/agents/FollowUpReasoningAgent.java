package com.example.BACKEND.catalogue.agent.agents;

import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Enriches chatbot responses with smart follow-up suggestions.
 *
 * After a user asks a question and gets back rows, this agent analyses:
 *   - The original question and the SQL that was generated
 *   - The actual data returned
 *   - The schema context (what other columns/tables exist)
 *
 * It then produces up to 4 follow-up suggestions:
 *   - Benchmark comparisons  ("How does this compare to last month?")
 *   - Segment breakdowns     ("Break this down by region")
 *   - Trend questions        ("Show me this as a trend over time")
 *   - Adjacent investigations ("Which products drove this?")
 *
 * These appear as clickable chips below each AI response in the chatbot.
 * Clicking a chip automatically sends it as the next question.
 */
@Service
public class FollowUpReasoningAgent {

    private static final int MAX_ROWS_FOR_CONTEXT = 5;
    private static final int MAX_SUGGESTIONS      = 4;

    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;

    public FollowUpReasoningAgent(OpenAiClient openAiClient, ObjectMapper objectMapper) {
        this.openAiClient = openAiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Generates follow-up question suggestions based on the query result.
     *
     * @param question   The original natural-language question
     * @param sql        The SQL that was executed
     * @param rows       The data returned
     * @param schemaHint A compact schema description for context
     * @return Up to MAX_SUGGESTIONS follow-up question strings
     */
    public List<String> suggest(String question,
                                String sql,
                                List<Map<String, Object>> rows,
                                String schemaHint) {
        if (rows == null || rows.isEmpty()) {
            return List.of("Why did this return no results?",
                           "Show me all available data",
                           "List the available columns");
        }

        String prompt = buildPrompt(question, sql, rows, schemaHint);

        try {
            String response = openAiClient.chat(
                    "You are a data analyst assistant. Generate smart follow-up question suggestions. " +
                    "Respond only with a JSON object.",
                    prompt
            );
            return parseSuggestions(response);
        } catch (Exception e) {
            System.out.printf("[FollowUpAgent] Failed: %s%n", e.getMessage());
            return buildFallbackSuggestions(question, rows);
        }
    }

    // ── Prompt builder ────────────────────────────────────────────────────────

    private String buildPrompt(String question, String sql,
                                List<Map<String, Object>> rows, String schemaHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("The user asked: \"").append(question).append("\"\n");
        sb.append("SQL executed: ").append(sql).append("\n");
        sb.append("Result (").append(rows.size()).append(" rows):\n");

        int show = Math.min(rows.size(), MAX_ROWS_FOR_CONTEXT);
        if (!rows.isEmpty()) {
            sb.append(String.join(", ", rows.get(0).keySet())).append("\n");
            for (int i = 0; i < show; i++) {
                sb.append(String.join(" | ", rows.get(i).values().stream()
                        .map(v -> v == null ? "null" : v.toString()).toList())).append("\n");
            }
        }
        if (rows.size() > show) sb.append("... (").append(rows.size() - show).append(" more rows)\n");

        if (schemaHint != null && !schemaHint.isBlank()) {
            sb.append("\nAvailable schema context:\n").append(schemaHint).append("\n");
        }

        sb.append("\nGenerate exactly ").append(MAX_SUGGESTIONS).append(" smart follow-up questions.\n");
        sb.append("Focus on: benchmarks vs prior period, dimension breakdowns, trends over time, adjacent insights.\n");
        sb.append("Make questions specific and directly actionable — not generic.\n");
        sb.append("Respond with JSON: {\"suggestions\": [\"question 1\", \"question 2\", ...]}");

        return sb.toString();
    }

    private List<String> parseSuggestions(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            List<String> suggestions = new ArrayList<>();
            for (JsonNode s : root.path("suggestions")) {
                String text = s.asText().trim();
                if (!text.isBlank()) suggestions.add(text);
            }
            return suggestions.isEmpty() ? List.of() :
                    suggestions.subList(0, Math.min(suggestions.size(), MAX_SUGGESTIONS));
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> buildFallbackSuggestions(String question, List<Map<String, Object>> rows) {
        List<String> suggestions = new ArrayList<>();
        if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
            String firstCol = rows.get(0).keySet().iterator().next();
            suggestions.add("Show me " + firstCol + " trend over time");
            suggestions.add("Compare this to the previous period");
        }
        suggestions.add("Break this down by category");
        suggestions.add("Which segment has the highest value?");
        return suggestions.subList(0, Math.min(suggestions.size(), MAX_SUGGESTIONS));
    }
}
