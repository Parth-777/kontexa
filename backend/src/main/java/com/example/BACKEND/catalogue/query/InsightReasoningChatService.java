package com.example.BACKEND.catalogue.query;

import com.example.BACKEND.catalogue.entity.InsightCardEntity;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.example.BACKEND.catalogue.repository.InsightCardRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers strategic / interpretive questions using persisted insight cards —
 * no warehouse SQL. Used for "why did this happen?", "what should we do?", etc.
 */
@Service
public class InsightReasoningChatService {

    private final OpenAiClient openAiClient;
    private final InsightCardRepository insightCardRepository;
    private final ObjectMapper objectMapper;

    public InsightReasoningChatService(
            OpenAiClient openAiClient,
            InsightCardRepository insightCardRepository,
            ObjectMapper objectMapper
    ) {
        this.openAiClient = openAiClient;
        this.insightCardRepository = insightCardRepository;
        this.objectMapper = objectMapper;
    }

    public ChatOrchestratorService.ChatResponse answer(
            String question, String clientId, List<ChatOrchestratorService.ChatTurn> history) {
        List<InsightCardEntity> cards = loadInsightContext(clientId);

        if (cards.isEmpty()) {
            return new ChatOrchestratorService.ChatResponse(
                    "reasoning",
                    "There are no active insights yet. Click **Refresh Insights** on the Agent Feed "
                            + "first, then ask about findings, risks, or recommended actions.",
                    null, List.of(), 0,
                    List.of(
                            "Show me a summary of the data",
                            "What are the top categories by volume?",
                            "List the most recent records"
                    ));
        }

        try {
            String system = """
                    You are Kontexa's executive analytics copilot.
                    
                    The user is asking about INSIGHTS already generated for their business — not raw SQL.
                    Answer using ONLY the insight cards and evidence provided below.
                    
                    RULES:
                    - Speak in clear executive English (no snake_case column names in prose).
                    - Reference specific numbers, segments, and recommendations from the cards.
                    - For "why" questions: synthesize from WHY THIS HAPPENED bullets and evidence.
                    - For "what should we do": use RECOMMENDED STRATEGIES and add practical next steps.
                    - If the question targets one card, focus on it; otherwise synthesize across cards.
                    - Do NOT say you need to run SQL or query the database.
                    - If evidence is insufficient, say what is known and what to validate next.
                    
                    Respond with JSON only:
                    {
                      "answer": "3-6 sentence professional answer",
                      "followUpSuggestions": ["...", "...", "..."]
                    }
                    """;

            String user = "USER QUESTION:\n\"" + question + "\"\n\n"
                    + "RECENT CHAT CONTEXT:\n" + formatHistory(history) + "\n\n"
                    + "ACTIVE INSIGHT CARDS:\n"
                    + formatCards(cards);

            String raw = openAiClient.chat(system, user);
            JsonNode node = objectMapper.readTree(cleanJson(raw));
            String answer = node.path("answer").asText("").trim();
            List<String> followUps = parseStringList(node.path("followUpSuggestions"));

            if (answer.isBlank()) {
                answer = "Based on your current insights, the main themes are: "
                        + cards.get(0).getTitle() + ".";
            }

            return new ChatOrchestratorService.ChatResponse(
                    "reasoning", answer, null, List.of(), 0, followUps);

        } catch (Exception e) {
            return ChatOrchestratorService.ChatResponse.error(
                    "Could not answer from insights: " + e.getMessage());
        }
    }

    private List<InsightCardEntity> loadInsightContext(String clientId) {
        insightCardRepository.expireOldCards(clientId, LocalDateTime.now());
        List<InsightCardEntity> active = insightCardRepository
                .findByClientIdAndStatusOrderByGeneratedAtDesc(clientId, "AWAITING_CONFIRMATION");
        if (!active.isEmpty()) return active;

        return insightCardRepository
                .findByClientIdAndStatusNotOrderByGeneratedAtDesc(clientId, "EXPIRED")
                .stream()
                .filter(c -> !"DECLINED".equals(c.getStatus()))
                .limit(8)
                .toList();
    }

    private String formatCards(List<InsightCardEntity> cards) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(cards.size(), 6);
        for (int i = 0; i < limit; i++) {
            InsightCardEntity c = cards.get(i);
            sb.append("\n--- Card ").append(i + 1).append(" ---\n");
            sb.append("Title: ").append(c.getTitle()).append("\n");
            if (c.getDescription() != null) sb.append("So What: ").append(c.getDescription()).append("\n");
            if (c.getBadge() != null) sb.append("Badge: ").append(c.getBadge()).append("\n");
            if (c.getAgentName() != null) sb.append("Lens: ").append(c.getAgentName()).append("\n");

            String highlights = parseJsonArray(c.getMetricHighlights());
            if (!highlights.isBlank()) sb.append("Metrics: ").append(highlights).append("\n");

            String reasons = parseJsonArray(c.getReasons());
            if (!reasons.isBlank()) sb.append("Why this happened:\n").append(reasons).append("\n");

            String strategies = parseJsonArray(c.getStrategies());
            if (!strategies.isBlank()) sb.append("Recommended strategies:\n").append(strategies).append("\n");

            if (c.getRawEvidence() != null && c.getRawEvidence().length() > 20) {
                String ev = c.getRawEvidence();
                if (ev.length() > 1200) ev = ev.substring(0, 1200) + "...";
                sb.append("Evidence snapshot: ").append(ev).append("\n");
            }
        }
        return sb.toString();
    }

    private String parseJsonArray(String json) {
        if (json == null || json.isBlank()) return "";
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) return json;
            StringBuilder sb = new StringBuilder();
            for (JsonNode n : arr) {
                if (n.isObject()) {
                    String label = n.path("label").asText("");
                    String value = n.path("value").asText("");
                    if (!label.isBlank() || !value.isBlank()) {
                        sb.append("  - ").append(label).append(": ").append(value).append("\n");
                    }
                } else {
                    sb.append("  - ").append(n.asText()).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return json;
        }
    }

    private String formatHistory(List<ChatOrchestratorService.ChatTurn> history) {
        if (history == null || history.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (ChatOrchestratorService.ChatTurn turn : history) {
            String role = "user".equalsIgnoreCase(turn.role()) ? "User" : "Assistant";
            if (turn.text() != null && !turn.text().isBlank()) {
                sb.append("- ").append(role).append(": ").append(turn.text()).append("\n");
            }
        }
        return sb.toString().trim();
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

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        raw = raw.trim();
        if (raw.startsWith("```")) {
            raw = raw.replaceAll("(?is)^```json\\s*", "").replaceAll("(?is)^```\\s*", "")
                    .replaceAll("```\\s*$", "").trim();
        }
        return raw;
    }
}
