package com.example.BACKEND.catalogue.agent.agents;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
import com.example.BACKEND.catalogue.agent.CollectedData;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extrapolates future values from historical trend data.
 *
 * Approach: LLM-based extrapolation (no statistical library required).
 *   1. Finds all "KPI:" or "Trend:" collected data sets that have ≥6 ordered time periods
 *   2. Sends the time series to the LLM with the instruction to forecast the next 3 periods
 *   3. Interprets the forecast direction (UP / DOWN / FLAT) to assign a badge:
 *        Positive trend continuation → OPPORTUNITY
 *        Negative trend continuation → RISK
 *        Reversal predicted          → ALERT
 *   4. Returns InsightCards with the forecast summary
 *
 * LLM extrapolation works well for business time series because:
 *   - It captures seasonal patterns from the data narrative
 *   - It reasons about likely business cycles (e.g. holiday seasonality)
 *   - It communicates uncertainty naturally in plain English
 */
@Service
public class ForecastingAgent {

    private static final int MIN_PERIODS_FOR_FORECAST = 6;
    private static final int FORECAST_PERIODS         = 3;

    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;

    public ForecastingAgent(OpenAiClient openAiClient, ObjectMapper objectMapper) {
        this.openAiClient = openAiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Generates forecast insight cards from the collected trend/KPI data.
     *
     * @param collected  All data collected by the orchestrator's per-table loop
     * @param tableName  Source table name (for card labelling)
     * @return List of InsightCards with forecast summaries
     */
    public List<AgentDashboardResult.InsightCard> forecast(
            List<CollectedData> collected, String tableName) {

        List<AgentDashboardResult.InsightCard> cards = new ArrayList<>();

        for (CollectedData data : collected) {
            // Only process time-series data sets
            if (!data.label().startsWith("Trend:") && !data.label().startsWith("KPI:")) continue;
            if (data.rows().size() < MIN_PERIODS_FOR_FORECAST) continue;

            try {
                AgentDashboardResult.InsightCard card = forecastSeries(data, tableName);
                if (card != null) cards.add(card);
            } catch (Exception e) {
                System.out.printf("[ForecastAgent] Failed for [%s]: %s%n",
                        data.label(), e.getMessage());
            }
        }

        return cards;
    }

    // ── Per-series forecasting ────────────────────────────────────────────────

    private AgentDashboardResult.InsightCard forecastSeries(
            CollectedData data, String tableName) {

        String seriesText = buildSeriesText(data.rows());
        String metricName = extractMetricName(data.label());

        String prompt =
                "You are a business forecaster. Here is a time series for '" + metricName +
                "' in '" + tableName + "':\n\n" +
                seriesText + "\n\n" +
                "The data is ordered from most recent to oldest.\n" +
                "Forecast the next " + FORECAST_PERIODS + " periods.\n\n" +
                "Respond with JSON:\n" +
                "{\n" +
                "  \"forecastSummary\": \"2-sentence forecast with predicted direction and reasoning\",\n" +
                "  \"forecastedValues\": [\"Period 1: ~X\", \"Period 2: ~X\", \"Period 3: ~X\"],\n" +
                "  \"trend\": \"UP | DOWN | FLAT\",\n" +
                "  \"confidence\": 0-100,\n" +
                "  \"riskFactors\": [\"factor 1\", \"factor 2\"],\n" +
                "  \"opportunities\": [\"action 1\", \"action 2\"]\n" +
                "}";

        String response = openAiClient.chat(
                "You are a quantitative analyst. Extrapolate from the data pattern. " +
                "Be specific about predicted values and explain the reasoning. " +
                "Acknowledge uncertainty appropriately.",
                prompt
        );

        return parseCard(response, metricName, tableName);
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private AgentDashboardResult.InsightCard parseCard(
            String response, String metric, String tableName) {
        try {
            JsonNode node = objectMapper.readTree(response);

            String trend   = node.path("trend").asText("FLAT");
            int confidence = node.path("confidence").asInt(60);
            String summary = node.path("forecastSummary").asText("");

            if (summary.isBlank()) return null;

            // Badge based on trend direction
            String badge = switch (trend) {
                case "UP"   -> "OPPORTUNITY";
                case "DOWN" -> "RISK";
                default     -> "INFO";
            };

            String impactLevel = confidence >= 75 ? "HIGH" :
                                 confidence >= 50 ? "MEDIUM" : "LOW";

            String title = "Forecast: " + metric + " projected to " +
                    (trend.equals("UP") ? "grow" : trend.equals("DOWN") ? "decline" : "stay flat") +
                    " over next " + FORECAST_PERIODS + " periods";

            AgentDashboardResult.InsightCard card =
                    new AgentDashboardResult.InsightCard(title, summary, impactLevel);
            card.setBadge(badge);
            card.setAgentName("Forecasting agent");

            // Forecasted values as metric highlights
            List<AgentDashboardResult.MetricHighlight> highlights = new ArrayList<>();
            int i = 1;
            for (JsonNode v : node.path("forecastedValues")) {
                if (i > 3) break;
                highlights.add(new AgentDashboardResult.MetricHighlight(
                        "Period +" + i, v.asText()));
                i++;
            }
            card.setMetricHighlights(highlights);

            // Risk factors as reasons
            List<String> reasons = new ArrayList<>();
            for (JsonNode r : node.path("riskFactors")) reasons.add(r.asText());
            card.setReasons(reasons);

            // Opportunities as strategies
            List<String> strategies = new ArrayList<>();
            for (JsonNode o : node.path("opportunities")) strategies.add(o.asText());
            card.setStrategies(strategies);

            System.out.printf("[ForecastAgent] %s → %s (confidence=%d%%)%n",
                    metric, trend, confidence);
            return card;

        } catch (Exception e) {
            System.out.printf("[ForecastAgent] Parse failed: %s%n", e.getMessage());
            return null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildSeriesText(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        String[] keys = rows.get(0).keySet().toArray(new String[0]);
        sb.append(String.join(" | ", keys)).append("\n");
        for (Map<String, Object> row : rows) {
            sb.append(String.join(" | ", java.util.Arrays.stream(keys)
                    .map(k -> row.get(k) == null ? "null" : row.get(k).toString())
                    .toList())).append("\n");
        }
        return sb.toString();
    }

    private String extractMetricName(String label) {
        return label.replace("Trend: ", "").replace("KPI: ", "").replace(" over time", "").trim();
    }
}
