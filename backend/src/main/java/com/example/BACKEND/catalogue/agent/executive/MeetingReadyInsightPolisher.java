package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
import com.example.BACKEND.catalogue.agent.CollectedData;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.example.BACKEND.catalogue.service.TenantBusinessProfileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Final pass: insight cards suitable for VP/COO slide decks — distinct headline vs So What,
 * correct badges, and specific strategies (owner + lever + target + timeframe).
 */
@Service
public class MeetingReadyInsightPolisher {

    private static final Set<String> GENERIC_STRATEGY_PHRASES = Set.of(
            "implement cost control",
            "monitor closely",
            "investigate further",
            "consider analyzing",
            "review drivers",
            "optimize operations",
            "improve margins",
            "review operational efficiency"
    );

    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;
    private final TenantBusinessProfileService tenantProfileService;

    public MeetingReadyInsightPolisher(
            OpenAiClient openAiClient,
            ObjectMapper objectMapper,
            TenantBusinessProfileService tenantProfileService
    ) {
        this.openAiClient = openAiClient;
        this.objectMapper = objectMapper;
        this.tenantProfileService = tenantProfileService;
    }

    public void polish(String clientId,
                       List<AgentDashboardResult.InsightCard> cards,
                       List<CollectedData> collected) {
        if (cards == null || cards.isEmpty()) return;

        for (AgentDashboardResult.InsightCard card : cards) {
            applyProgrammaticBadge(card);
            dedupeTitleAndDescription(card);
        }

        try {
            String system = ExecutiveVoice.PERSONA + """

                    TASK: Rewrite insight cards for a BOARD MEETING slide deck.
                    
                    For EACH card:
                    - title: Slide headline (max 14 words). Pattern: [What moved] — [business implication].
                      Do NOT start with "Root cause:". No snake_case. No repetition of the description.
                    - description: Exactly ONE sentence — So What (stakes for P&L, cash, or volume).
                      Must add NEW insight vs the title (not a paraphrase).
                    - badge: ALERT (margin/cost deterioration), OPPORTUNITY (revenue/volume upside),
                      RISK (concentration, volatility, sustainability risk). Revenue surge = OPPORTUNITY not RISK.
                    - reasons: 2 bullets, past tense, each with a number ($, %, barrels, bps).
                    - strategies: 2 bullets. Format EXACTLY:
                      "Owner — specific lever (what to change) — measurable target — timeframe"
                      Examples:
                      "Finance — cap discretionary opex at $X/week — restore margin to 15%+ — this quarter"
                      "Sales — renegotiate term contracts in top 3 accounts — +8% realized price — 60 days"
                    
                    BANNED in strategies: "implement cost control", "monitor", "investigate", "review efficiency"
                    
                    JSON only:
                    {"cards":[{"index":0,"title":"...","description":"...","badge":"ALERT","reasons":["...","..."],"strategies":["...","..."]}]}
                    """;

            String user = tenantProfileService.promptBlock(clientId)
                    + "\nCARDS TO POLISH:\n"
                    + formatCardsForPrompt(cards)
                    + "\nEVIDENCE (use only these numbers):\n"
                    + formatEvidence(collected);

            String response = openAiClient.chat(system, user);
            applyLlmPolish(cards, response);
            System.out.printf("[MeetingReadyPolisher] Polished %d cards%n", cards.size());
        } catch (Exception e) {
            System.out.printf("[MeetingReadyPolisher] LLM polish skipped: %s%n", e.getMessage());
            for (AgentDashboardResult.InsightCard card : cards) {
                dedupeTitleAndDescription(card);
                applyProgrammaticBadge(card);
            }
        }
    }

    private void applyLlmPolish(List<AgentDashboardResult.InsightCard> cards, String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            for (JsonNode item : root.path("cards")) {
                int idx = item.path("index").asInt(-1);
                if (idx < 0 || idx >= cards.size()) continue;
                AgentDashboardResult.InsightCard card = cards.get(idx);

                String title = item.path("title").asText("").trim();
                if (!title.isBlank()) card.setTitle(stripRootCausePrefix(title));

                String desc = item.path("description").asText("").trim();
                if (!desc.isBlank() && !isNearDuplicate(card.getTitle(), desc)) {
                    card.setDescription(desc);
                }

                String badge = item.path("badge").asText("").trim().toUpperCase(Locale.ROOT);
                if (Set.of("ALERT", "RISK", "OPPORTUNITY", "INFO").contains(badge)) {
                    card.setBadge(badge);
                }

                List<String> reasons = parseStringArray(item.path("reasons"));
                if (reasons.size() >= 2) card.setReasons(reasons);

                List<String> strategies = filterStrategies(parseStringArray(item.path("strategies")));
                if (strategies.size() >= 2) card.setStrategies(strategies);

                dedupeTitleAndDescription(card);
                applyProgrammaticBadge(card);
            }
        } catch (Exception e) {
            System.out.printf("[MeetingReadyPolisher] Parse failed: %s%n", e.getMessage());
        }
    }

    /** Revenue up → OPPORTUNITY; margin/cost down → ALERT; cost up → ALERT. */
    public static void applyProgrammaticBadge(AgentDashboardResult.InsightCard card) {
        if (card == null) return;
        String combined = ((card.getTitle() == null ? "" : card.getTitle()) + " "
                + (card.getDescription() == null ? "" : card.getDescription())).toLowerCase(Locale.ROOT);

        boolean negative = combined.contains("dropped") || combined.contains("declined")
                || combined.contains("fell") || combined.contains("compressed")
                || combined.contains("down ") || combined.matches(".*-\\d+%.*");
        boolean positive = combined.contains("surged") || combined.contains("rose")
                || combined.contains("increased") || combined.contains("grew")
                || combined.matches(".*\\+\\d+%.*");

        if (combined.contains("margin") || combined.contains("profit")) {
            card.setBadge(negative ? "ALERT" : "OPPORTUNITY");
            return;
        }
        if (combined.contains("cost") || combined.contains("opex") || combined.contains("expense")) {
            card.setBadge(positive ? "ALERT" : "OPPORTUNITY");
            return;
        }
        if (combined.contains("revenue") || combined.contains("sales") || combined.contains("barrel")) {
            card.setBadge(positive ? "OPPORTUNITY" : "ALERT");
            return;
        }
        if (negative) card.setBadge("ALERT");
        else if (positive) card.setBadge("OPPORTUNITY");
    }

    public static void dedupeTitleAndDescription(AgentDashboardResult.InsightCard card) {
        if (card == null) return;
        String title = card.getTitle() == null ? "" : card.getTitle().trim();
        String desc  = card.getDescription() == null ? "" : card.getDescription().trim();

        if (title.isBlank() || desc.isBlank()) return;

        if (isNearDuplicate(title, desc)) {
            if (card.getReasons() != null && !card.getReasons().isEmpty()) {
                card.setDescription(card.getReasons().get(0));
            } else {
                card.setDescription(buildSoWhatFromTitle(title));
            }
        }
    }

    private static String buildSoWhatFromTitle(String title) {
        String t = stripRootCausePrefix(title);
        if (t.toLowerCase(Locale.ROOT).contains("margin")) {
            return "Margin pressure directly threatens full-year profitability unless cost and price levers are reset.";
        }
        if (t.toLowerCase(Locale.ROOT).contains("revenue")) {
            return "Revenue volatility affects cash planning — leadership should validate sustainability before resetting targets.";
        }
        return "This shift is material to the operating plan and requires an owner-assigned response this quarter.";
    }

    static boolean isNearDuplicate(String title, String description) {
        String a = normalize(title);
        String b = normalize(description);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (b.startsWith(a) || a.startsWith(b)) return true;
        if (b.contains(a) || a.contains(b)) return true;
        return similarity(a, b) > 0.72;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9%$\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static double similarity(String a, String b) {
        Set<String> wa = tokenize(a);
        Set<String> wb = tokenize(b);
        if (wa.isEmpty() || wb.isEmpty()) return 0;
        long overlap = wa.stream().filter(wb::contains).count();
        return (2.0 * overlap) / (wa.size() + wb.size());
    }

    /** Set.of() rejects duplicate tokens; titles often repeat words like "the". */
    private static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (String word : text.split(" ")) {
            if (!word.isBlank()) tokens.add(word);
        }
        return tokens;
    }

    public static String stripRootCausePrefixPublic(String title) {
        return stripRootCausePrefix(title);
    }

    private static String stripRootCausePrefix(String title) {
        String t = title.trim();
        if (t.regionMatches(true, 0, "Root cause:", 0, 11)) {
            return t.substring(11).trim();
        }
        if (t.regionMatches(true, 0, "Root cause —", 0, 12)) {
            return t.substring(12).trim();
        }
        return t;
    }

    private List<String> filterStrategies(List<String> strategies) {
        List<String> out = new ArrayList<>();
        for (String s : strategies) {
            if (s == null || s.isBlank()) continue;
            String lower = s.toLowerCase(Locale.ROOT);
            boolean generic = GENERIC_STRATEGY_PHRASES.stream().anyMatch(lower::contains);
            if (!generic) out.add(s.trim());
        }
        return out;
    }

    private List<String> parseStringArray(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String s = n.asText("").trim();
                if (!s.isBlank()) out.add(s);
            }
        }
        return out;
    }

    private String formatCardsForPrompt(List<AgentDashboardResult.InsightCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            AgentDashboardResult.InsightCard c = cards.get(i);
            sb.append("index: ").append(i).append("\n");
            sb.append("title: ").append(c.getTitle()).append("\n");
            sb.append("description: ").append(c.getDescription()).append("\n");
            sb.append("badge: ").append(c.getBadge()).append("\n");
            sb.append("agent: ").append(c.getAgentName()).append("\n");
            if (c.getMetricHighlights() != null) {
                for (var h : c.getMetricHighlights()) {
                    sb.append("  ").append(h.getLabel()).append(": ").append(h.getValue()).append("\n");
                }
            }
            if (c.getReasons() != null) {
                for (String r : c.getReasons()) sb.append("reason: ").append(r).append("\n");
            }
            if (c.getStrategies() != null) {
                for (String s : c.getStrategies()) sb.append("strategy: ").append(s).append("\n");
            }
            sb.append("---\n");
        }
        return sb.toString();
    }

    private String formatEvidence(List<CollectedData> collected) {
        if (collected == null) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (CollectedData cd : collected) {
            if (n >= 12) break;
            if (cd.label() != null && cd.label().startsWith("Sample rows")) continue;
            sb.append(cd.label()).append("\n");
            if (cd.rows() != null) {
                int rows = Math.min(4, cd.rows().size());
                for (int i = 0; i < rows; i++) sb.append(cd.rows().get(i)).append("\n");
            }
            n++;
        }
        return sb.toString();
    }
}
