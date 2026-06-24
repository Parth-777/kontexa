package com.example.BACKEND.catalogue.query;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Unified analyst chat — reasoning-first professional assistant.
 */
@Service
public class ChatOrchestratorService {

    private static final Pattern PURE_INSIGHT_QUESTION = Pattern.compile(
            "\\b(why did|why is|why are|what should we|what can we do|explain (this|the) insight|"
                    + "leadership brief|recommended strateg|so what|what happened with this)\\b",
            Pattern.CASE_INSENSITIVE);

    private final GeneralDataChatService generalChat;
    private final InsightReasoningChatService insightOnlyChat;

    public ChatOrchestratorService(
            GeneralDataChatService generalChat,
            InsightReasoningChatService insightOnlyChat
    ) {
        this.generalChat = generalChat;
        this.insightOnlyChat = insightOnlyChat;
    }

    /** Route pure insight wording to insight-focused service; otherwise analyst reasoning. */
    public ChatResponse handle(String question, String clientId) {
        return handle(question, clientId, List.of());
    }

    public ChatResponse handle(String question, String clientId, List<ChatTurn> history) {
        if (PURE_INSIGHT_QUESTION.matcher(question).find()) {
            System.out.printf("[Chat] route=insight-only question=\"%s\"%n",
                    truncate(question));
            return insightOnlyChat.answer(question, clientId, history);
        }

        System.out.printf("[Chat] route=analyst question=\"%s\"%n", truncate(question));
        return generalChat.chat(question, clientId, history);
    }

    public RouteType classifyRoute(String question) {
        return classifyRoute(question, null);
    }

    public RouteType classifyRoute(String question, String clientId) {
        return RouteType.REASONING;
    }

    private static String truncate(String q) {
        return q.length() > 80 ? q.substring(0, 80) + "..." : q;
    }

    public enum RouteType { SQL, REASONING, MIXED }

    public record ChatTurn(String role, String text) {}

    public record ChatResponse(
            String type,
            String answer,
            String sql,
            List<Map<String, Object>> rows,
            int rowCount,
            List<String> followUpSuggestions
    ) {
        public static ChatResponse error(String message) {
            return new ChatResponse("error", message, null, List.of(), 0, List.of());
        }
    }
}
