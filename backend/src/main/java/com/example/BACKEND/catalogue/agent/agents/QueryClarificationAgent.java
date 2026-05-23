package com.example.BACKEND.catalogue.agent.agents;

import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Intercepts ambiguous NLP queries before SQL is generated.
 *
 * Problem: "show me revenue" → what time period? which table? which currency?
 * Without clarification, the SQL might return a misleading aggregate.
 *
 * Strategy:
 *   1. Given the question + schema context, ask the LLM:
 *      "Is this question specific enough to write accurate SQL?"
 *   2. If NO → return a targeted clarifying question to the user
 *   3. If YES → proceed with normal SQL generation
 *
 * The clarification check uses a fast, cheap LLM call (the prompt is tiny).
 * It only triggers when the question genuinely lacks key context —
 * not on every query (to avoid annoying users with unnecessary questions).
 *
 * Result shape: ClarificationResult
 *   needsClarification = false → proceed normally
 *   needsClarification = true  → return clarifyingQuestion to the frontend
 */
@Service
public class QueryClarificationAgent {

    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;

    public QueryClarificationAgent(OpenAiClient openAiClient, ObjectMapper objectMapper) {
        this.openAiClient = openAiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Checks whether a question is specific enough for accurate SQL generation.
     *
     * @param question    The raw NLP question from the user
     * @param schemaHint  Compact schema summary (table + column names + business meanings)
     * @return ClarificationResult indicating whether to proceed or ask a follow-up
     */
    public ClarificationResult check(String question, String schemaHint) {
        String prompt = buildPrompt(question, schemaHint);

        try {
            String response = openAiClient.chat(
                    "You are a query quality checker. Determine if a user's question is specific enough " +
                    "for accurate SQL generation. Respond only with a JSON object.",
                    prompt
            );
            return parse(response);
        } catch (Exception e) {
            System.out.printf("[ClarificationAgent] Check failed (proceeding): %s%n", e.getMessage());
            // On failure, always proceed — don't block the user
            return ClarificationResult.proceed();
        }
    }

    // ── Prompt ────────────────────────────────────────────────────────────────

    private String buildPrompt(String question, String schemaHint) {
        return "User question: \"" + question + "\"\n\n" +
               "Schema context:\n" + schemaHint + "\n\n" +
               "A question needs clarification when it is missing ANY of:\n" +
               "  - Time period (when there is a date column and no time context is given)\n" +
               "  - Key filter (e.g. 'revenue' but 10 product categories exist — which one?)\n" +
               "  - Ambiguous metric (multiple numeric columns match the intent)\n\n" +
               "A question does NOT need clarification when:\n" +
               "  - It asks for a full overview ('show me everything', 'list all', 'top 10')\n" +
               "  - The schema has only one obvious match\n" +
               "  - It already specifies time period, category, or filter clearly\n\n" +
               "Respond with:\n" +
               "{\"needsClarification\": false} if the question is clear enough, OR\n" +
               "{\"needsClarification\": true, \"clarifyingQuestion\": \"A single specific question to ask the user\"}";
    }

    private ClarificationResult parse(String response) {
        try {
            JsonNode node = objectMapper.readTree(response);
            boolean needs = node.path("needsClarification").asBoolean(false);
            if (!needs) return ClarificationResult.proceed();
            String q = node.path("clarifyingQuestion").asText("").trim();
            return q.isBlank() ? ClarificationResult.proceed() : ClarificationResult.clarify(q);
        } catch (Exception e) {
            return ClarificationResult.proceed();
        }
    }

    // ── Result type ───────────────────────────────────────────────────────────

    public record ClarificationResult(boolean needsClarification, String clarifyingQuestion) {
        public static ClarificationResult proceed() {
            return new ClarificationResult(false, null);
        }
        public static ClarificationResult clarify(String question) {
            return new ClarificationResult(true, question);
        }
    }
}
