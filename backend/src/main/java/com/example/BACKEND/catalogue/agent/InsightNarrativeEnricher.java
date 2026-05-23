package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites "WHY THIS HAPPENED" and "RECOMMENDED STRATEGIES" for every insight card
 * using OpenAI and the full collected dataset from the agent run.
 *
 * Ensures professional, past-tense, data-backed narratives — never raw ReAct step
 * text like "I will analyze...".
 */
@Service
public class InsightNarrativeEnricher {

    private static final int MAX_DATASETS     = 18;
    private static final int ROWS_PER_DATASET = 8;

    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;

    public InsightNarrativeEnricher(OpenAiClient openAiClient, ObjectMapper objectMapper) {
        this.openAiClient = openAiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Overwrites reasons + strategies on each card using the shared data context.
     */
    public void enrichAll(List<AgentDashboardResult.InsightCard> cards,
                          List<CollectedData> collected) {
        if (cards == null || cards.isEmpty()) return;

        String dataContext  = formatCollectedData(collected);
        String cardsContext = formatCards(cards);

        String systemPrompt = """
                You are a senior business analyst writing insight card copy for executives.
                
                You receive:
                1) Raw query results from the company's database (the ground truth)
                2) A list of insight headlines already generated
                
                For EACH insight, write:
                - reasons: exactly 2-3 bullets explaining WHY this happened (past tense, established facts)
                - strategies: exactly 2-3 concrete recommended actions for the business
                
                STRICT RULES:
                - Use ONLY facts supported by the data provided. Cite specific numbers, dates, percentages.
                - NEVER write in future/planning voice: no "I will", "To investigate", "we should analyze",
                  "this will help identify", or incomplete sentences.
                - Each reason must be a complete finding, e.g. "Market price per barrel rose from $82.09
                  (Jan 2023) to $119.88 (Aug 2024), driving the revenue spike."
                - Strategies must be actionable business moves, not "run more analysis".
                - Use exact column values and category names from the data — never invent labels.
                
                Respond with JSON only:
                {
                  "cards": [
                    {"index": 0, "reasons": ["...", "..."], "strategies": ["...", "..."]},
                    ...
                  ]
                }
                """;

        String userPrompt =
                "DATABASE QUERY RESULTS (ground truth):\n" +
                "=====================================\n" + dataContext + "\n\n" +
                "INSIGHTS TO ENRICH:\n" +
                "==================\n" + cardsContext;

        try {
            String response = openAiClient.chat(systemPrompt, userPrompt);
            JsonNode root     = objectMapper.readTree(response);
            JsonNode cardArr  = root.path("cards");

            if (!cardArr.isArray()) {
                System.out.println("[InsightNarrativeEnricher] No cards array in response — skipping");
                return;
            }

            for (JsonNode item : cardArr) {
                int idx = item.path("index").asInt(-1);
                if (idx < 0 || idx >= cards.size()) continue;

                List<String> reasons = parseList(item.path("reasons"));
                List<String> strategies = parseList(item.path("strategies"));

                if (!reasons.isEmpty())     cards.get(idx).setReasons(reasons);
                if (!strategies.isEmpty())  cards.get(idx).setStrategies(strategies);
            }

            System.out.printf("[InsightNarrativeEnricher] Enriched %d insight cards%n", cards.size());

        } catch (Exception e) {
            System.out.printf("[InsightNarrativeEnricher] Enrichment failed (keeping prior text): %s%n",
                    e.getMessage());
        }
    }

    private String formatCollectedData(List<CollectedData> collected) {
        if (collected == null || collected.isEmpty()) return "(no query data)";

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (CollectedData cd : collected) {
            if (count >= MAX_DATASETS) {
                sb.append("\n... (additional datasets omitted for brevity)\n");
                break;
            }
            sb.append("\n## ").append(cd.label()).append(" (").append(cd.rows().size()).append(" rows)\n");
            List<java.util.Map<String, Object>> rows = cd.rows();
            if (rows.isEmpty()) {
                sb.append("(empty)\n");
                count++;
                continue;
            }
            int show = Math.min(rows.size(), ROWS_PER_DATASET);
            sb.append(String.join(" | ", rows.get(0).keySet())).append("\n");
            for (int i = 0; i < show; i++) {
                sb.append(String.join(" | ", rows.get(i).values().stream()
                        .map(v -> v == null ? "null" : v.toString()).toList())).append("\n");
            }
            if (rows.size() > show) sb.append("... ").append(rows.size() - show).append(" more rows\n");
            count++;
        }
        return sb.toString();
    }

    private String formatCards(List<AgentDashboardResult.InsightCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            AgentDashboardResult.InsightCard c = cards.get(i);
            sb.append("index: ").append(i).append("\n");
            sb.append("title: ").append(c.getTitle()).append("\n");
            sb.append("description: ").append(c.getDescription()).append("\n");
            sb.append("agent: ").append(c.getAgentName()).append("\n");
            if (c.getMetricHighlights() != null) {
                sb.append("metrics: ");
                for (AgentDashboardResult.MetricHighlight h : c.getMetricHighlights()) {
                    sb.append(h.getLabel()).append("=").append(h.getValue()).append("; ");
                }
            }
            sb.append("\n---\n");
        }
        return sb.toString();
    }

    private List<String> parseList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String s = n.asText("").trim();
                if (!s.isBlank() && !looksLikePlanStep(s)) out.add(s);
            }
        }
        return out;
    }

    /** Filter out ReAct planning phrases if they slip through. */
    private boolean looksLikePlanStep(String s) {
        String lower = s.toLowerCase();
        return lower.startsWith("to investigate") || lower.startsWith("to further investigate") ||
               lower.contains("i will analyze") || lower.contains("i will ") ||
               lower.contains("this will help identify") || lower.contains("→ recorded_date");
    }
}