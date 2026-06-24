package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
import com.example.BACKEND.catalogue.agent.CollectedData;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.example.BACKEND.catalogue.service.TenantBusinessProfileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Narrates verified {@link InsightCandidate}s in C-suite voice (So What / Why / Now What).
 */
@Service
public class ExecutiveNarrator {

    private static final int MAX_DATASETS = 15;
    private static final int ROWS_PER_DS  = 6;
    private static final int BRIEF_MAX_CHARS = 520;

    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;
    private final TenantBusinessProfileService tenantProfileService;

    public ExecutiveNarrator(
            OpenAiClient openAiClient,
            ObjectMapper objectMapper,
            TenantBusinessProfileService tenantProfileService
    ) {
        this.openAiClient = openAiClient;
        this.objectMapper = objectMapper;
        this.tenantProfileService = tenantProfileService;
    }

    /**
     * Builds insight cards directly from verified lens candidates (no LLM).
     * Used when narration fails evidence checks or as a reliable fallback.
     */
    public List<AgentDashboardResult.InsightCard> programmaticCards(List<InsightCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        return templateCards(candidates);
    }

    /**
     * Turns ranked candidates into executive insight cards via a single narration call.
     */
    public List<AgentDashboardResult.InsightCard> narrateCandidates(
            String clientId,
            List<InsightCandidate> candidates,
            List<CollectedData> collected) {

        if (candidates == null || candidates.isEmpty()) return List.of();

        String system = buildSystemPrompt();
        String user   = buildUserPrompt(clientId, candidates, collected);

        try {
            String response = openAiClient.chat(system, user);
            return parseCards(response, candidates);
        } catch (Exception e) {
            System.out.printf("[ExecutiveNarrator] Narration failed, using template cards: %s%n",
                    e.getMessage());
            return templateCards(candidates);
        }
    }

    /**
     * Leadership Brief — 2–3 sentence board-level summary (never raw column dumps).
     */
    public String buildDailyBrief(
            String clientId,
            List<AgentDashboardResult.InsightCard> insightCards,
            List<InsightCandidate> topCandidates,
            List<CollectedData> collected) {

        if ((insightCards == null || insightCards.isEmpty())
                && (topCandidates == null || topCandidates.isEmpty())) {
            return "No material shifts warrant executive attention in this cycle.";
        }

        try {
            String system = ExecutiveVoice.PERSONA + """

                    TASK: Write the LEADERSHIP BRIEF — the first thing a President/CEO reads.
                    
                    OUTPUT:
                    - Exactly 2–3 complete sentences (max 480 characters total)
                    - Prose paragraph only — NO numbered lists, NO bullet points, NO snake_case
                    - Synthesize the top 3 priorities; connect revenue, margin, and volume where relevant
                    - End with implied urgency (what leadership should focus on first)
                    
                    JSON only: {"leadershipBrief":"..."}
                    """;

            String user = tenantProfileService.promptBlock(clientId)
                    + "\nTOP PRIORITIES (narrate in executive English):\n"
                    + formatBriefInputs(insightCards, topCandidates)
                    + "\nEVIDENCE (numbers only — do not quote column names):\n"
                    + formatCollectedData(collected);

            String response = openAiClient.chat(system, user);
            JsonNode root = objectMapper.readTree(response);
            String brief = root.path("leadershipBrief").asText("").trim();
            if (brief.isBlank()) {
                brief = root.path("brief").asText("").trim();
            }
            if (!brief.isBlank()) {
                return trimBrief(brief);
            }
        } catch (Exception e) {
            System.out.printf("[ExecutiveNarrator] Leadership brief LLM failed: %s%n", e.getMessage());
        }

        return fallbackBrief(insightCards, topCandidates);
    }

    /**
     * Rewrites reasons + strategies on all cards (post-merge with root cause / forecast).
     */
    public void enrichAll(String clientId,
                          List<AgentDashboardResult.InsightCard> cards,
                          List<CollectedData> collected) {
        if (cards == null || cards.isEmpty()) return;

        String profileBlock = tenantProfileService.promptBlock(clientId);
        String dataContext  = formatCollectedData(collected);
        String cardsContext = formatCards(cards);

        String systemPrompt = ExecutiveVoice.PERSONA + """

                For EACH card, rewrite:
                - description: one sentence — the So What (business consequence, not methodology)
                - reasons: exactly 2 bullets — Why (past tense, numbers from data)
                - strategies: exactly 2 bullets — Now What (Owner: Sales|Product|Finance|Ops — action — timeframe)
                
                Use formatted numbers ($59.5M, 245%, 7.7 pts). Never expose raw database field names.
                
                JSON only:
                {"cards":[{"index":0,"description":"...","reasons":["...","..."],"strategies":["...","..."]}]}
                """ + profileBlock;

        String userPrompt =
                "EVIDENCE:\n" + dataContext + "\n\nCARDS:\n" + cardsContext;

        try {
            String response = openAiClient.chat(systemPrompt, userPrompt);
            JsonNode root = objectMapper.readTree(response);
            for (JsonNode item : root.path("cards")) {
                int idx = item.path("index").asInt(-1);
                if (idx < 0 || idx >= cards.size()) continue;

                String desc = item.path("description").asText("");
                if (!desc.isBlank()) cards.get(idx).setDescription(desc);

                List<String> reasons = parseList(item.path("reasons"));
                List<String> strategies = parseList(item.path("strategies"));
                if (!reasons.isEmpty()) cards.get(idx).setReasons(reasons);
                if (!strategies.isEmpty()) cards.get(idx).setStrategies(strategies);
            }
            System.out.printf("[ExecutiveNarrator] Enriched %d cards%n", cards.size());
        } catch (Exception e) {
            System.out.printf("[ExecutiveNarrator] Enrichment skipped: %s%n", e.getMessage());
        }
    }

    private String formatBriefInputs(
            List<AgentDashboardResult.InsightCard> cards,
            List<InsightCandidate> candidates) {

        StringBuilder sb = new StringBuilder();
        int n = 0;

        if (cards != null) {
            for (AgentDashboardResult.InsightCard c : cards) {
                if (n >= 3) break;
                if (c == null || isInternalAgent(c.getAgentName())) continue;
                sb.append(n + 1).append(". ")
                        .append(c.getTitle() != null ? c.getTitle() : "")
                        .append(" — ")
                        .append(c.getDescription() != null ? c.getDescription() : "")
                        .append(" [").append(c.getBadge()).append("]\n");
                n++;
            }
        }

        if (n == 0 && candidates != null) {
            for (InsightCandidate c : candidates) {
                if (n >= 3) break;
                sb.append(n + 1).append(". [").append(c.lens().agentLabel()).append("] ")
                        .append(humanizeClaim(c.claim())).append("\n");
                n++;
            }
        }
        return sb.toString();
    }

    private boolean isInternalAgent(String agentName) {
        if (agentName == null) return false;
        String lower = agentName.toLowerCase();
        return lower.contains("forecast");
    }

    private String fallbackBrief(
            List<AgentDashboardResult.InsightCard> cards,
            List<InsightCandidate> candidates) {

        StringBuilder sb = new StringBuilder();
        sb.append("This week: ");

        int added = 0;
        if (cards != null) {
            for (AgentDashboardResult.InsightCard c : cards) {
                if (added >= 3) break;
                if (c == null || isInternalAgent(c.getAgentName())) continue;
                String title = c.getTitle();
                if (title == null || title.isBlank()) continue;
                if (added > 0) sb.append(" ");
                sb.append(title);
                if (added < 2) sb.append(" ·");
                added++;
            }
        }

        if (added == 0 && candidates != null) {
            for (InsightCandidate c : candidates) {
                if (added >= 3) break;
                if (added > 0) sb.append(" ");
                sb.append(humanizeClaim(c.claim()));
                if (added < 2) sb.append(" ·");
                added++;
            }
        }

        if (added == 0) {
            return "No material shifts warrant executive attention in this cycle.";
        }
        sb.append(" Leadership should review the detailed cards below and assign owners.");
        return trimBrief(sb.toString());
    }

    private String trimBrief(String brief) {
        String t = brief.trim().replaceAll("\\s+", " ");
        if (t.length() <= BRIEF_MAX_CHARS) return t;
        int cut = t.lastIndexOf('.', BRIEF_MAX_CHARS - 1);
        if (cut > 120) return t.substring(0, cut + 1);
        return t.substring(0, BRIEF_MAX_CHARS - 3) + "...";
    }

    private String humanizeClaim(String claim) {
        if (claim == null) return "";
        String out = claim;
        if (out.contains(" anomaly: ")) {
            int idx = out.indexOf(" anomaly: ");
            out = ExecutiveVoice.humanizeMetric(out.substring(0, idx))
                    + " — " + out.substring(idx + " anomaly: ".length());
        }
        return out.replace('_', ' ');
    }

    private String buildSystemPrompt() {
        return ExecutiveVoice.PERSONA + """

                You receive VERIFIED insight candidates — do not invent new facts or metrics.
                
                For each candidate, produce one card:
                - title: max 12 words — plain-English metric name, direction, delta, business implication
                - description: 1 sentence So What (business consequence for the CEO)
                - metricHighlights: exactly 3 chips labeled Size, Change, Where — formatted values only
                - reasons: 2 data-backed bullets (past tense, $ and % formatted)
                - strategies: 2 Now What bullets with Owner (Sales/Product/Finance/Ops) + action + timeframe
                - badge, impactLevel, agentName: copy from candidate (agentName = lens label)
                - sourceColumns: from candidate (internal only; do not show snake_case in title/description)
                
                JSON only:
                {"insights":[{...}]}
                """;
    }

    private String buildUserPrompt(String clientId, List<InsightCandidate> candidates,
                                    List<CollectedData> collected) {
        StringBuilder sb = new StringBuilder();
        sb.append(tenantProfileService.promptBlock(clientId)).append("\n");
        sb.append("VERIFIED CANDIDATES (narrate ONLY these — translate column names to business labels):\n");
        for (int i = 0; i < candidates.size(); i++) {
            InsightCandidate c = candidates.get(i);
            sb.append(i).append(". [").append(c.lens().agentLabel()).append("] ")
                    .append(humanizeClaim(c.claim())).append("\n");
            sb.append("   badge=").append(c.badge())
                    .append(" impact=").append(c.impactLevel())
                    .append(" evidence=").append(c.evidenceRefs()).append("\n");
            if (c.driverSegment() != null) {
                sb.append("   driver_segment=").append(c.driverSegment()).append("\n");
            }
            if (c.metricHighlights() != null) {
                for (var h : c.metricHighlights()) {
                    sb.append("   ").append(h.getLabel()).append("=")
                            .append(h.getValue()).append("\n");
                }
            }
        }
        sb.append("\nSUPPORTING EVIDENCE:\n");
        sb.append(formatCollectedData(collected));
        return sb.toString();
    }

    private List<AgentDashboardResult.InsightCard> parseCards(String response,
                                                               List<InsightCandidate> candidates) {
        try {
            JsonNode root = objectMapper.readTree(response);
            List<AgentDashboardResult.InsightCard> cards = new ArrayList<>();
            int i = 0;
            for (JsonNode n : root.path("insights")) {
                InsightCandidate src = i < candidates.size() ? candidates.get(i) : candidates.get(0);
                String title = n.path("title").asText("");
                if (title.isBlank()) title = humanizeClaim(src.claim());

                AgentDashboardResult.InsightCard card = new AgentDashboardResult.InsightCard(
                        title,
                        n.path("description").asText(humanizeClaim(src.claim())),
                        n.path("impactLevel").asText(src.impactLevel())
                );
                card.setBadge(n.path("badge").asText(src.badge()));
                card.setAgentName(n.path("agentName").asText(src.lens().agentLabel()));

                List<AgentDashboardResult.MetricHighlight> highlights = new ArrayList<>();
                if (n.has("metricHighlights") && n.path("metricHighlights").isArray()) {
                    for (JsonNode h : n.path("metricHighlights")) {
                        highlights.add(new AgentDashboardResult.MetricHighlight(
                                h.path("label").asText(""),
                                formatHighlightValue(h.path("value").asText(""))));
                    }
                } else if (src.metricHighlights() != null) {
                    for (var h : src.metricHighlights()) {
                        highlights.add(new AgentDashboardResult.MetricHighlight(
                                h.getLabel(), formatHighlightValue(h.getValue())));
                    }
                }
                card.setMetricHighlights(highlights);

                List<String> reasons = new ArrayList<>();
                for (JsonNode r : n.path("reasons")) reasons.add(r.asText());
                card.setReasons(reasons.isEmpty() ? List.of(humanizeClaim(src.claim())) : reasons);

                List<String> strategies = new ArrayList<>();
                for (JsonNode s : n.path("strategies")) strategies.add(s.asText());
                card.setStrategies(strategies);

                List<String> cols = new ArrayList<>();
                for (JsonNode c : n.path("sourceColumns")) cols.add(c.asText());
                card.setSourceColumns(cols.isEmpty() ? src.sourceColumns() : cols);

                cards.add(card);
                i++;
            }
            return cards.isEmpty() ? templateCards(candidates) : cards;
        } catch (Exception e) {
            return templateCards(candidates);
        }
    }

    private String formatHighlightValue(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        try {
            double d = Double.parseDouble(raw.replace(",", "").trim());
            if (raw.contains("E") || raw.contains("e") || Math.abs(d) >= 10_000) {
                return ExecutiveVoice.formatValue(d);
            }
        } catch (NumberFormatException ignored) {
            // keep as-is
        }
        return raw;
    }

    private List<AgentDashboardResult.InsightCard> templateCards(List<InsightCandidate> candidates) {
        List<AgentDashboardResult.InsightCard> cards = new ArrayList<>();
        for (InsightCandidate c : candidates) {
            String headline = humanizeClaim(c.claim());
            AgentDashboardResult.InsightCard card = new AgentDashboardResult.InsightCard(
                    headline, headline, c.impactLevel());
            card.setBadge(c.badge());
            card.setAgentName(c.lens().agentLabel());
            card.setMetricHighlights(c.metricHighlights());
            card.setSourceColumns(c.sourceColumns());
            card.setReasons(List.of(headline));
            card.setStrategies(List.of(
                    c.suggestedOwner() + ": Align cross-functional response within 30 days."));
            cards.add(card);
        }
        return cards;
    }

    private String formatCollectedData(List<CollectedData> collected) {
        if (collected == null) return "";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (CollectedData cd : collected) {
            if (count >= MAX_DATASETS) break;
            if (cd.label() != null && cd.label().startsWith("Sample rows")) continue;
            sb.append("\n## ").append(cd.label()).append("\n");
            var rows = cd.rows();
            int show = Math.min(rows.size(), ROWS_PER_DS);
            for (int i = 0; i < show; i++) {
                sb.append(rows.get(i)).append("\n");
            }
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
            sb.append("agent: ").append(c.getAgentName()).append("\n---\n");
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

    private boolean looksLikePlanStep(String s) {
        String lower = s.toLowerCase();
        return lower.contains("i will") || lower.startsWith("to investigate")
                || lower.contains("monitor closely") || lower.contains("consider analyzing");
    }
}
