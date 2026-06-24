package com.example.BACKEND.catalogue.query;

import com.example.BACKEND.catalogue.entity.InsightCardEntity;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Professional analyst chatbot: plain-English reasoning on insights + schema context. */
@Service
public class GeneralDataChatService {

    private final OpenAiClient openAiClient;
    private final CatalogueApprovalService approvalService;
    private final CataloguePromptBuilder promptBuilder;
    private final ChatContextBuilder chatContextBuilder;
    private final ObjectMapper objectMapper;

    public GeneralDataChatService(
            OpenAiClient openAiClient,
            CatalogueApprovalService approvalService,
            CataloguePromptBuilder promptBuilder,
            ChatContextBuilder chatContextBuilder,
            ObjectMapper objectMapper
    ) {
        this.openAiClient = openAiClient;
        this.approvalService = approvalService;
        this.promptBuilder = promptBuilder;
        this.chatContextBuilder = chatContextBuilder;
        this.objectMapper = objectMapper;
    }

    public ChatOrchestratorService.ChatResponse chat(
            String question, String clientId, List<ChatOrchestratorService.ChatTurn> history) {
        try {
            String snapshotJson = approvalService.getApprovedSnapshot(clientId);
            JsonNode catalogueNode = objectMapper.readTree(snapshotJson);
            List<InsightCardEntity> insights = chatContextBuilder.loadActiveInsights(clientId);

            String systemPrompt = buildAgentSystemPrompt(catalogueNode, insights);
            String userPrompt = """
                    USER QUESTION:
                    "%s"

                    RECENT CHAT CONTEXT:
                    %s

                    Give a clear plain-English answer as a senior analyst.
                    Use ACTIVE INSIGHTS + SCHEMA context. Do not output SQL.
                    Output JSON only:
                    {
                      "answer":"2-4 sentence direct answer",
                      "evidence":["bullet with concrete number/observation", "..."],
                      "nextActions":["actionable recommendation", "..."],
                      "followUpSuggestions":["...", "...", "..."]
                    }
                    """.formatted(question, formatHistory(history));

            String llmJson = openAiClient.chat(systemPrompt, userPrompt);
            JsonNode node = objectMapper.readTree(cleanJson(llmJson));
            String answer = node.path("answer").asText("").trim();
            List<String> evidence = parseStringList(node.path("evidence"));
            List<String> nextActions = parseStringList(node.path("nextActions"));
            List<String> followUps = parseStringList(node.path("followUpSuggestions"));

            if (isWeakAnswer(answer)) {
                answer = fallbackAnswerFromInsights(question, insights);
            }
            answer = formatFinalAnswer(answer, evidence, nextActions, insights, catalogueNode);

            return new ChatOrchestratorService.ChatResponse(
                    "reasoning", answer, null, List.of(), 0, followUps);

        } catch (Exception e) {
            return ChatOrchestratorService.ChatResponse.error(
                    "Chat failed: " + e.getMessage());
        }
    }

    private boolean isWeakAnswer(String answer) {
        if (answer == null || answer.isBlank()) return true;
        String lower = answer.toLowerCase();
        return lower.equals("found 0 rows.")
                || lower.equals("found 0 rows")
                || lower.startsWith("found 0 row")
                || lower.length() < 20;
    }

    private String fallbackAnswerFromInsights(String question, List<InsightCardEntity> insights) {
        if (insights == null || insights.isEmpty()) {
            return "I could not retrieve matching rows for that question. "
                    + "Try asking with a specific metric and dimension from your schema, e.g. "
                    + "\"trip count by pickup zone, lowest first, using dates in the dataset\".";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Based on your latest Agent Feed insights: ");
        sb.append(insights.get(0).getTitle());
        if (insights.get(0).getDescription() != null) {
            sb.append(" — ").append(insights.get(0).getDescription());
        }
        if (insights.size() > 1) {
            sb.append(" Also note: ").append(insights.get(1).getTitle()).append(".");
        }
        sb.append(" For \"").append(question).append("\", I recommend rephrasing with a measurable definition ")
                .append("(e.g. zones with fewest trips vs fleet average in the same 150-day window).");
        return sb.toString();
    }

    private String formatHistory(List<ChatOrchestratorService.ChatTurn> history) {
        if (history == null || history.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (ChatOrchestratorService.ChatTurn turn : history) {
            String role = "user".equalsIgnoreCase(turn.role()) ? "User" : "Assistant";
            sb.append("- ").append(role).append(": ").append(turn.text()).append("\n");
        }
        return sb.toString().trim();
    }

    private String formatFinalAnswer(
            String answer, List<String> evidence, List<String> nextActions,
            List<InsightCardEntity> insights, JsonNode catalogueNode) {
        StringBuilder sb = new StringBuilder(answer == null ? "" : answer.trim());
        String answerLower = sb.toString().toLowerCase(Locale.ROOT);
        if (answerLower.contains("do not have specific data")
                || answerLower.contains("don't have specific data")
                || answerLower.contains("no specific data")) {
            String fallbackProxy = firstGenericProxyHint(catalogueNode);
            if (fallbackProxy != null && !fallbackProxy.isBlank()) {
                sb.append("\n\nAssumption used: ").append(fallbackProxy).append(".");
            }
        }
        List<String> evidenceFinal = (evidence == null || evidence.isEmpty())
                ? fallbackEvidenceFromInsights(insights)
                : evidence.stream().filter(s -> s != null && !s.isBlank()).limit(3).toList();

        if (!evidenceFinal.isEmpty()) {
            sb.append("\n\nKey evidence:");
            for (String e : evidenceFinal) sb.append("\n- ").append(e);
        }
        if (nextActions != null && !nextActions.isEmpty()) {
            sb.append("\n\nRecommended next actions:");
            nextActions.stream().filter(s -> s != null && !s.isBlank()).limit(3)
                    .forEach(a -> sb.append("\n- ").append(a));
        }
        return sb.toString().trim();
    }

    private List<String> fallbackEvidenceFromInsights(List<InsightCardEntity> insights) {
        if (insights == null || insights.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        int limit = Math.min(2, insights.size());
        for (int i = 0; i < limit; i++) {
            InsightCardEntity c = insights.get(i);
            if (c.getTitle() != null && !c.getTitle().isBlank()) out.add(c.getTitle());
            if (out.size() >= 3) break;
        }
        return out;
    }

    private String buildAgentSystemPrompt(JsonNode catalogueNode, List<InsightCardEntity> insights) {
        String schemaBlock = promptBuilder.buildSystemPromptFromSnapshot(catalogueNode);
        String insightBlock = chatContextBuilder.formatInsights(insights);

        return """
                You are Kontexa's Senior Data Analyst — the same intelligence behind the Agent Feed insights.
                Answer in plain English like an executive analyst.
                Do not generate SQL and do not mention SQL in the response.

                Use these sources:
                1) ACTIVE INSIGHTS below — verified findings with reasons/strategies.
                2) SCHEMA below — to understand what data exists.

                ANSWER RULES:
                - Cite numbers only if present in ACTIVE INSIGHTS context.
                - If evidence is partial, explicitly state assumptions.
                - Professional, concise, no snake_case in prose.
                - Include practical next actions when user asks recommendations.
                - End with 2-3 suggested follow-up questions.
                - If user wording does not exactly match a column name, infer the closest concept
                  using column names, descriptions, business meanings, and sample values.
                - Never stop at "no data" if a reasonable semantic proxy exists in schema context.
                - Keep responses generic and schema-grounded; do not assume a fixed domain.

                ACTIVE INSIGHTS:
                """ + insightBlock + "\n\nSCHEMA:\n" + schemaBlock;
    }

    private String firstGenericProxyHint(JsonNode catalogueNode) {
        for (JsonNode table : catalogueNode.path("tables")) {
            for (JsonNode col : table.path("columns")) {
                String name = col.path("columnName").asText("").trim();
                if (name.isBlank()) continue;
                String meaning = col.path("businessMeaning").asText("").trim();
                if (!meaning.isBlank()) {
                    return "mapping business term to column '" + name + "' (" + meaning + ")";
                }
            }
        }
        return "mapping business term to the closest schema concept by column meaning";
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
