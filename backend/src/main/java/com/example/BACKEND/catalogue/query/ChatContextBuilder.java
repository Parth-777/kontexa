package com.example.BACKEND.catalogue.query;

import com.example.BACKEND.catalogue.entity.InsightCardEntity;
import com.example.BACKEND.catalogue.repository.InsightCardRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Shared context for chat: approved schema, active insights, and data date anchors.
 */
@Component
public class ChatContextBuilder {

    private final InsightCardRepository insightCardRepository;
    private final ObjectMapper objectMapper;

    public ChatContextBuilder(InsightCardRepository insightCardRepository, ObjectMapper objectMapper) {
        this.insightCardRepository = insightCardRepository;
        this.objectMapper = objectMapper;
    }

    public List<InsightCardEntity> loadActiveInsights(String clientId) {
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

    public String formatInsights(List<InsightCardEntity> cards) {
        if (cards == null || cards.isEmpty()) {
            return "(No insight cards yet — run Refresh Insights on the Agent Feed.)\n";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(cards.size(), 6);
        for (int i = 0; i < limit; i++) {
            InsightCardEntity c = cards.get(i);
            sb.append("\n--- Insight ").append(i + 1).append(" ---\n");
            sb.append("Title: ").append(c.getTitle()).append("\n");
            if (c.getDescription() != null) sb.append("Summary: ").append(c.getDescription()).append("\n");
            if (c.getBadge() != null) sb.append("Badge: ").append(c.getBadge()).append("\n");
            String reasons = parseJsonArray(c.getReasons());
            if (!reasons.isBlank()) sb.append("Why:\n").append(reasons);
            String strategies = parseJsonArray(c.getStrategies());
            if (!strategies.isBlank()) sb.append("Strategies:\n").append(strategies);
            if (c.getRawEvidence() != null && c.getRawEvidence().length() > 20) {
                String ev = c.getRawEvidence();
                if (ev.length() > 1500) ev = ev.substring(0, 1500) + "...";
                sb.append("Evidence: ").append(ev).append("\n");
            }
        }
        return sb.toString();
    }

    public String formatDateAnchors(JsonNode catalogueNode) {
        StringBuilder sb = new StringBuilder();
        sb.append("DATA DATE RANGES (anchor SQL to these — do NOT use CURRENT_DATE as the only filter):\n");
        boolean any = false;
        for (JsonNode table : catalogueNode.path("tables")) {
            String tableName = table.path("tableName").asText("");
            for (JsonNode col : table.path("columns")) {
                String colName = col.path("columnName").asText("");
                String maxVal = col.path("maxValue").asText("");
                String minVal = col.path("minValue").asText("");
                String type = col.path("dataType").asText("").toLowerCase();
                boolean dateLike = type.contains("date") || type.contains("time")
                        || colName.toLowerCase().contains("date")
                        || colName.toLowerCase().contains("time");
                if (dateLike && maxVal.length() >= 10) {
                    sb.append("  - ").append(tableName).append(".").append(colName)
                            .append(": ").append(minVal.length() >= 10 ? minVal.substring(0, 10) : "?")
                            .append(" → ").append(maxVal.substring(0, Math.min(10, maxVal.length()))).append("\n");
                    any = true;
                }
            }
        }
        if (!any) {
            sb.append("  (No max dates in catalogue — run SELECT MIN/MAX on the main date column before filtering.)\n");
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
                    sb.append("  - ").append(n.path("label").asText(""))
                            .append(": ").append(n.path("value").asText("")).append("\n");
                } else {
                    sb.append("  - ").append(n.asText()).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return json;
        }
    }
}
