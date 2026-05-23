package com.example.BACKEND.catalogue.query;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Thin wrapper — all chat traffic goes to {@link GeneralDataChatService}:
 * OpenAI explores the database with multiple SQL steps, then answers.
 */
@Service
public class ChatOrchestratorService {

    private final GeneralDataChatService generalChat;

    public ChatOrchestratorService(GeneralDataChatService generalChat) {
        this.generalChat = generalChat;
    }

    public ChatResponse handle(String question, String clientId) {
        return generalChat.chat(question, clientId);
    }

    /** Kept for API compatibility — general chat always uses the data agent. */
    public RouteType classifyRoute(String question) {
        return RouteType.SQL;
    }

    public RouteType classifyRoute(String question, String clientId) {
        return RouteType.SQL;
    }

    public enum RouteType { SQL, REASONING, MIXED }

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
