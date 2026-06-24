package com.example.BACKEND.catalogue.agent.agents;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
import com.example.BACKEND.catalogue.agent.EnrichedColInfo;
import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.catalogue.agent.executive.ExecutiveVoice;
import com.example.BACKEND.catalogue.agent.executive.MeetingReadyInsightPolisher;
import com.example.BACKEND.catalogue.agent.scale.AgentSqlHelper;
import com.example.BACKEND.catalogue.agent.scale.ScaleAwareQueryExecutor;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Investigates the root cause of statistical anomalies using the ReAct pattern.
 *
 * ReAct = Reason + Act (each step the LLM reasons about what to query, then Java runs it)
 *
 * Loop per anomaly (max MAX_STEPS iterations):
 *  ┌─────────────────────────────────────────────────────────────────┐
 *  │  THINK: LLM analyses history → proposes next SQL drill-down     │
 *  │  ACT  : Java executes the SQL against the live data source       │
 *  │  OBSERVE: Result fed back to LLM as next iteration context      │
 *  └─────────────────────────────────────────────────────────────────┘
 *  CONCLUDE: LLM writes a 2-3 sentence root cause + recommended actions
 *
 * Only HIGH-significance anomalies are investigated (to control LLM cost).
 * Results are returned as InsightCards; reasons/strategies are synthesised
 * in the CONCLUDE step (or by InsightNarrativeEnricher after the orchestrator run).
 *
 * SQL safety: only SELECT statements accepted from the LLM;
 * anything else is rejected and the step is skipped.
 */
@Service
public class RootCauseAnalysisAgent {

    private static final int    MAX_STEPS          = 3;
    private static final int    RESULT_ROWS_SHOWN   = 10;
    private static final double HIGH_CHANGE_THRESHOLD = 15.0;  // only investigate ≥15% moves

    private final OpenAiClient             openAiClient;
    private final ObjectMapper             objectMapper;
    private final ScaleAwareQueryExecutor  queryExecutor;

    public RootCauseAnalysisAgent(
            OpenAiClient              openAiClient,
            ObjectMapper              objectMapper,
            ScaleAwareQueryExecutor   queryExecutor
    ) {
        this.openAiClient    = openAiClient;
        this.objectMapper    = objectMapper;
        this.queryExecutor   = queryExecutor;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Investigates each high-significance anomaly and returns InsightCards
     * with the full root-cause reasoning chain.
     */
    public List<AgentDashboardResult.InsightCard> investigate(
            List<AgentDashboardResult.Anomaly> anomalies,
            TableContext ctx,
            JsonNode catalogueNode
    ) {
        List<AgentDashboardResult.InsightCard> cards = new ArrayList<>();

        for (AgentDashboardResult.Anomaly anomaly : anomalies) {
            if (Math.abs(anomaly.getChangePercent()) < HIGH_CHANGE_THRESHOLD) continue;

            try {
                AgentDashboardResult.InsightCard card =
                        runReActLoop(anomaly, ctx, catalogueNode);
                if (card != null) cards.add(card);
            } catch (Exception e) {
                System.out.printf("[RootCause] Investigation failed for %s: %s%n",
                        anomaly.getMetric(), e.getMessage());
            }
        }

        return cards;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ReAct loop
    // ─────────────────────────────────────────────────────────────────────────

    private AgentDashboardResult.InsightCard runReActLoop(
            AgentDashboardResult.Anomaly anomaly,
            TableContext ctx,
            JsonNode catalogueNode
    ) {
        String schemaContext = buildSchemaContext(ctx, catalogueNode);
        List<ReActStep> history = new ArrayList<>();
        boolean done = false;

        for (int step = 0; step < MAX_STEPS && !done; step++) {
            // THINK: ask LLM what to query next
            ThinkResult think = think(anomaly, schemaContext, history, ctx);
            if (think == null) break;

            done = think.done();
            if (think.sql() == null || think.sql().isBlank()) break;

            // Safety: only allow SELECT
            String sql = think.sql().trim();
            if (!sql.toUpperCase().startsWith("SELECT")) {
                System.out.printf("[RootCause] Rejected non-SELECT SQL at step %d%n", step);
                break;
            }

            // ACT: run the SQL
            List<Map<String, Object>> rows = safeExecute(sql, ctx);
            String observation = formatRows(rows);

            history.add(new ReActStep(think.thought(), sql, observation, rows.size()));
            System.out.printf("[RootCause] Step %d: %s → %d rows%n",
                    step + 1, think.thought().substring(0, Math.min(60, think.thought().length())), rows.size());

            if (done || rows.isEmpty()) break;
        }

        if (history.isEmpty()) return null;

        // CONCLUDE: ask LLM to synthesise the full reasoning chain
        return conclude(anomaly, history, ctx.hints().tableName());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // THINK step  — LLM proposes the next SQL query
    // ─────────────────────────────────────────────────────────────────────────

    private ThinkResult think(AgentDashboardResult.Anomaly anomaly,
                               String schemaContext,
                               List<ReActStep> history,
                               TableContext ctx) {
        String direction = anomaly.getDirection().equals("DOWN") ? "dropped" : "spiked";
        double absPct    = Math.abs(anomaly.getChangePercent());

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are investigating why '").append(anomaly.getMetric())
              .append("' ").append(direction).append(" by ")
              .append(String.format("%.1f%%", absPct)).append(".\n\n");

        prompt.append("TABLE SCHEMA:\n").append(schemaContext).append("\n\n");

        if (!history.isEmpty()) {
            prompt.append("INVESTIGATION SO FAR:\n");
            for (int i = 0; i < history.size(); i++) {
                ReActStep s = history.get(i);
                prompt.append("Step ").append(i + 1).append(": ").append(s.thought()).append("\n");
                prompt.append("SQL: ").append(s.sql()).append("\n");
                prompt.append("Result (").append(s.rowCount()).append(" rows): ").append(s.observation()).append("\n\n");
            }
        }

        int remaining = MAX_STEPS - history.size();
        prompt.append("You have ").append(remaining).append(" step(s) remaining.\n");
        prompt.append("If you have enough evidence to conclude, set done=true.\n\n");
        prompt.append("Respond with JSON: {\"thought\": \"...\", \"sql\": \"SELECT ...\", \"done\": false}");

        try {
            String response = openAiClient.chat(
                    "You are a data detective. Propose ONE SQL query to drill deeper into the root cause. " +
                    "Only write SELECT queries. Use exact column and table names from the schema.",
                    prompt.toString()
            );

            JsonNode node = objectMapper.readTree(response);
            return new ThinkResult(
                    node.path("thought").asText("Investigating..."),
                    node.path("sql").asText(""),
                    node.path("done").asBoolean(false)
            );
        } catch (Exception e) {
            System.out.printf("[RootCause] Think step failed: %s%n", e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONCLUDE step — LLM synthesises the full chain into an InsightCard
    // ─────────────────────────────────────────────────────────────────────────

    private AgentDashboardResult.InsightCard conclude(
            AgentDashboardResult.Anomaly anomaly,
            List<ReActStep> history,
            String tableName
    ) {
        String direction = anomaly.getDirection().equals("DOWN") ? "dropped" : "spiked";
        double absPct    = Math.abs(anomaly.getChangePercent());

        StringBuilder prompt = new StringBuilder();
        prompt.append("You investigated why '").append(anomaly.getMetric())
              .append("' ").append(direction).append(" ").append(String.format("%.1f%%", absPct))
              .append(" in '").append(tableName).append("'.\n\n");

        prompt.append("INVESTIGATION STEPS:\n");
        for (int i = 0; i < history.size(); i++) {
            ReActStep s = history.get(i);
            prompt.append("Step ").append(i + 1).append(": ").append(s.thought()).append("\n");
            prompt.append("Queried: ").append(s.sql()).append("\n");
            prompt.append("Found: ").append(s.observation()).append("\n\n");
        }

        prompt.append("Based on the QUERY RESULTS above (not the investigation plans), respond with JSON:\n");
        prompt.append("{\n");
        prompt.append("  \"title\": \"Slide headline: [metric] [direction] [%] — [P&L implication] (max 14 words, NO 'Root cause:' prefix)\",\n");
        prompt.append("  \"conclusion\": \"ONE sentence So What — stakes for leadership. Must NOT repeat the title.\",\n");
        prompt.append("  \"badge\": \"ALERT | OPPORTUNITY | RISK (revenue/volume up = OPPORTUNITY; margin down = ALERT)\",\n");
        prompt.append("  \"reasons\": [\n");
        prompt.append("    \"Past-tense driver with $ or % from Found data\",\n");
        prompt.append("    \"Second driver with date or segment\"\n");
        prompt.append("  ],\n");
        prompt.append("  \"strategies\": [\n");
        prompt.append("    \"Owner — specific lever — measurable target — timeframe\",\n");
        prompt.append("    \"Owner — specific lever — measurable target — timeframe\"\n");
        prompt.append("  ],\n");
        prompt.append("  \"metricHighlights\": [{\"label\": \"Size|Change|Driver\", \"value\": \"formatted $ or %\"}],\n");
        prompt.append("  \"confidence\": 0-100\n");
        prompt.append("}\n");
        prompt.append("NEVER put investigation plans in reasons. Only facts from Found: data.");

        try {
            String response = openAiClient.chat(
                    ExecutiveVoice.PERSONA + """
                            Conclude this root-cause investigation for the CEO.
                            Write reasons as past-tense facts with $ and % formatted (no scientific notation).
                            Never say 'I will analyze' or 'To investigate'. Use plain-English metric names only.
                            """,
                    prompt.toString()
            );

            JsonNode node = objectMapper.readTree(response);

            AgentDashboardResult.InsightCard card = new AgentDashboardResult.InsightCard(
                    node.path("title").asText(
                            anomaly.getMetric() + " " + direction + " " +
                            String.format("%.1f%%", absPct) + " — root cause identified"),
                    node.path("conclusion").asText(anomaly.getDescription()),
                    "HIGH"
            );

            String badge = node.path("badge").asText("").trim().toUpperCase();
            if (badge.isBlank() || !Set.of("ALERT", "RISK", "OPPORTUNITY", "INFO").contains(badge)) {
                MeetingReadyInsightPolisher.applyProgrammaticBadge(card);
            } else {
                card.setBadge(badge);
            }
            card.setAgentName("Root Cause agent");

            // Reasons/strategies: LLM synthesis only (ReAct steps stay internal)
            List<String> reasons = new ArrayList<>();
            for (JsonNode r : node.path("reasons")) {
                String reason = r.asText("").trim();
                if (!reason.isBlank()) reasons.add(reason);
            }
            card.setReasons(reasons);

            List<String> strategies = new ArrayList<>();
            for (JsonNode s : node.path("strategies")) strategies.add(s.asText());
            card.setStrategies(strategies);

            List<AgentDashboardResult.MetricHighlight> highlights = new ArrayList<>();
            for (JsonNode h : node.path("metricHighlights")) {
                highlights.add(new AgentDashboardResult.MetricHighlight(
                        ExecutiveVoice.humanizeMetric(h.path("label").asText("")),
                        formatHighlightValue(h.path("value").asText(""))));
            }
            // Always include the anomaly magnitude as first highlight
            highlights.add(0, new AgentDashboardResult.MetricHighlight(
                    ExecutiveVoice.humanizeMetric(anomaly.getMetric()) + " change",
                    (anomaly.getDirection().equals("DOWN") ? "-" : "+") +
                    ExecutiveVoice.formatPercent(absPct)));
            if (highlights.size() > 3) highlights = highlights.subList(0, 3);
            card.setMetricHighlights(highlights);

            card.setTitle(MeetingReadyInsightPolisher.stripRootCausePrefixPublic(card.getTitle()));
            MeetingReadyInsightPolisher.dedupeTitleAndDescription(card);

            System.out.printf("[RootCause] Concluded investigation for %s (confidence=%d)%n",
                    anomaly.getMetric(), node.path("confidence").asInt(70));

            return card;

        } catch (Exception e) {
            System.out.printf("[RootCause] Conclude step failed: %s%n", e.getMessage());
            // Return a basic card with the step observations if synthesis fails
            return buildFallbackCard(anomaly, history, tableName);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Schema context builder
    // ─────────────────────────────────────────────────────────────────────────

    private String buildSchemaContext(TableContext ctx, JsonNode catalogueNode) {
        StringBuilder sb = new StringBuilder();
        sb.append("Table: ").append(ctx.tableRef()).append("\n");
        sb.append("Columns:\n");

        for (JsonNode tableNode : catalogueNode.path("tables")) {
            if (!ctx.hints().tableName().equalsIgnoreCase(
                    tableNode.path("tableName").asText(""))) continue;

            for (JsonNode col : tableNode.path("columns")) {
                String name    = col.path("columnName").asText("");
                String type    = col.path("dataType").asText("?");
                String meaning = col.path("businessMeaning").asText("");
                String role    = col.path("role").asText("");

                sb.append("  - ").append(name)
                  .append(" (").append(type).append(")");
                if (!role.isBlank())    sb.append(" [").append(role).append("]");
                if (!meaning.isBlank()) sb.append(" — ").append(meaning);
                sb.append("\n");
            }
            break;
        }

        // Add dimension columns from other tables (for cross-table drill-downs)
        sb.append("\nAvailable dimension columns for joins:\n");
        sb.append("  Metric columns: ").append(String.join(", ", ctx.hints().numericCols())).append("\n");
        sb.append("  Dimension columns: ").append(String.join(", ", ctx.hints().stringCols())).append("\n");
        if (ctx.hints().dateCol() != null)
            sb.append("  Date column: ").append(ctx.hints().dateCol()).append("\n");

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private String formatRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return "(no rows returned)";
        int show = Math.min(rows.size(), RESULT_ROWS_SHOWN);
        StringBuilder sb = new StringBuilder();
        if (!rows.isEmpty()) {
            sb.append(String.join(", ", rows.get(0).keySet())).append("\n");
        }
        for (int i = 0; i < show; i++) {
            sb.append(String.join(" | ", rows.get(i).values().stream()
                    .map(v -> v == null ? "null" : v.toString()).toList())).append("\n");
        }
        if (rows.size() > show) sb.append("... (").append(rows.size() - show).append(" more)");
        return sb.toString().trim();
    }

    /**
     * Bounded drill-down for LARGE tables (no ReAct) — 1–2 aggregate queries only.
     */
    public List<AgentDashboardResult.InsightCard> investigateWithTemplates(
            List<AgentDashboardResult.Anomaly> anomalies,
            TableContext ctx,
            JsonNode catalogueNode
    ) {
        List<AgentDashboardResult.InsightCard> cards = new ArrayList<>();
        for (AgentDashboardResult.Anomaly anomaly : anomalies) {
            if (Math.abs(anomaly.getChangePercent()) < HIGH_CHANGE_THRESHOLD) continue;
            try {
                cards.add(buildTemplateCard(anomaly, ctx, catalogueNode));
            } catch (Exception e) {
                System.out.printf("[RootCause] Template drill-down failed for %s: %s%n",
                        anomaly.getMetric(), e.getMessage());
            }
        }
        return cards;
    }

    private AgentDashboardResult.InsightCard buildTemplateCard(
            AgentDashboardResult.Anomaly anomaly,
            TableContext ctx,
            JsonNode catalogueNode
    ) {
        String metric = anomaly.getMetric();
        String metricRef = AgentSqlHelper.qualify(metric, ctx.provider());
        String driverSegment = "overall";

        if (!ctx.hints().stringCols().isEmpty()) {
            String dim = ctx.hints().stringCols().get(0);
            String dimRef = AgentSqlHelper.qualify(dim, ctx.provider());
            EnrichedColInfo metricInfo = ctx.enriched().get(metric.toLowerCase());
            String agg = (metricInfo != null && List.of("SUM", "COUNT", "AVG").contains(metricInfo.aggregationMethod()))
                    ? metricInfo.aggregationMethod() : "SUM";
            String aggExpr = switch (agg) {
                case "COUNT" -> "COUNT(" + metricRef + ")";
                case "AVG" -> "AVG(" + metricRef + ")";
                default -> "SUM(" + metricRef + ")";
            };
            String sql = String.format(
                    "SELECT %s AS segment, %s AS seg_value FROM %s " +
                    "GROUP BY 1 ORDER BY seg_value DESC LIMIT 5",
                    dimRef, aggExpr, AgentSqlHelper.fromWithPredicates(ctx, dimRef + " IS NOT NULL"));
            List<Map<String, Object>> rows = queryExecutor.execute(sql, "RootCause template segment", ctx);
            if (!rows.isEmpty() && rows.get(0).get("segment") != null) {
                driverSegment = rows.get(0).get("segment").toString();
            }
        }

        String direction = anomaly.getDirection().equals("DOWN") ? "declined" : "increased";
        double absPct = Math.abs(anomaly.getChangePercent());
        String title = ExecutiveVoice.humanizeMetric(metric) + " " + direction + " "
                + ExecutiveVoice.formatPercent(absPct) + " — " + driverSegment + " leads within window";

        AgentDashboardResult.InsightCard card = new AgentDashboardResult.InsightCard(
                title,
                driverSegment.equals("overall")
                        ? "The shift is concentrated in the latest analysis window and warrants a targeted operational review."
                        : driverSegment + " is the largest contributor to the " + ExecutiveVoice.humanizeMetric(metric)
                        + " move in the analysis window.",
                "HIGH"
        );
        MeetingReadyInsightPolisher.applyProgrammaticBadge(card);
        card.setAgentName("Root Cause agent");
        card.setReasons(List.of(
                ExecutiveVoice.humanizeMetric(metric) + " " + direction + " "
                        + ExecutiveVoice.formatPercent(absPct) + " vs the prior monthly run-rate.",
                "Top segment in window: " + driverSegment + " (from guarded aggregate query)."
        ));
        card.setStrategies(List.of(
                "Finance — validate " + ExecutiveVoice.humanizeMetric(metric) + " drivers in "
                        + driverSegment + " — reconcile to plan within 14 days",
                "Ops — assign owner for " + driverSegment + " corrective actions — next 30 days"
        ));
        card.setMetricHighlights(List.of(
                new AgentDashboardResult.MetricHighlight(
                        ExecutiveVoice.humanizeMetric(metric) + " change",
                        (anomaly.getDirection().equals("DOWN") ? "-" : "+") + ExecutiveVoice.formatPercent(absPct)),
                new AgentDashboardResult.MetricHighlight("Driver", driverSegment),
                new AgentDashboardResult.MetricHighlight("Window",
                        ctx.window().active() ? ctx.window().start() + " → " + ctx.window().end() : "90d")
        ));
        card.setSourceColumns(List.of(metric));
        return card;
    }

    private List<Map<String, Object>> safeExecute(String sql, TableContext ctx) {
        if (sql == null || !sql.trim().toUpperCase().startsWith("SELECT")) {
            System.out.printf("[RootCause] Rejected non-SELECT SQL%n");
            return List.of();
        }
        return queryExecutor.execute(sql, "RootCause ReAct", ctx);
    }

    private AgentDashboardResult.InsightCard buildFallbackCard(
            AgentDashboardResult.Anomaly anomaly,
            List<ReActStep> history,
            String tableName
    ) {
        String direction = anomaly.getDirection().equals("DOWN") ? "dropped" : "spiked";
        AgentDashboardResult.InsightCard card = new AgentDashboardResult.InsightCard(
                anomaly.getMetric() + " " + direction + " "
                        + String.format("%.1f%%", Math.abs(anomaly.getChangePercent())),
                anomaly.getDescription(),
                "HIGH"
        );
        card.setBadge(anomaly.getDirection().equals("DOWN") ? "ALERT" : "RISK");
        card.setAgentName("Root Cause agent");

        card.setReasons(List.of(
                "Investigation completed — see description for findings.",
                anomaly.getDescription() != null ? anomaly.getDescription() : "Anomaly detected in " + anomaly.getMetric()
        ));
        card.setStrategies(List.of(
                "Review peak-period metrics with operations and finance teams.",
                "Monitor " + anomaly.getMetric() + " weekly to confirm whether the move persists."
        ));
        card.setMetricHighlights(List.of(
                new AgentDashboardResult.MetricHighlight(
                        anomaly.getMetric(),
                        (anomaly.getDirection().equals("DOWN") ? "-" : "+") +
                        String.format("%.1f%%", Math.abs(anomaly.getChangePercent())))
        ));
        return card;
    }

    private String formatHighlightValue(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        try {
            double d = Double.parseDouble(raw.replace(",", "").trim());
            if (raw.contains("E") || raw.contains("e") || Math.abs(d) >= 10_000) {
                return ExecutiveVoice.formatValue(d);
            }
        } catch (NumberFormatException ignored) {
            // keep literal
        }
        return raw;
    }

    // ── Internal value objects ────────────────────────────────────────────────

    private record ThinkResult(String thought, String sql, boolean done) {}

    private record ReActStep(
            String thought,
            String sql,
            String observation,
            int rowCount
    ) {}
}
