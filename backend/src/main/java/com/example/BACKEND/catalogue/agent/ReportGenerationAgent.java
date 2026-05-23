package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.entity.AgentReportEntity;
import com.example.BACKEND.catalogue.entity.InsightCardEntity;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.example.BACKEND.catalogue.repository.AgentReportRepository;
import com.example.BACKEND.catalogue.repository.InsightCardRepository;
import com.example.BACKEND.catalogue.repository.CatalogueSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Generates a weekly narrative report for each tenant by synthesising
 * all insight cards from the past 7 days into a single executive summary.
 *
 * The report reads like an analyst's briefing — not a bullet-point list —
 * covering: what happened, what's at risk, what opportunities exist,
 * and what actions are recommended.
 *
 * Runs automatically every Sunday at midnight (configurable via cron).
 * Reports are persisted in agent_reports and accessible via GET /api/agent/reports.
 */
@Service
public class ReportGenerationAgent {

    private static final int MAX_CARDS_IN_REPORT = 20;
    private static final DateTimeFormatter WEEK_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy");

    private final InsightCardRepository     insightCardRepo;
    private final AgentReportRepository     reportRepo;
    private final CatalogueSnapshotRepository snapshotRepo;
    private final OpenAiClient              openAiClient;
    private final ObjectMapper              objectMapper;

    public ReportGenerationAgent(
            InsightCardRepository      insightCardRepo,
            AgentReportRepository      reportRepo,
            CatalogueSnapshotRepository snapshotRepo,
            OpenAiClient               openAiClient,
            ObjectMapper               objectMapper
    ) {
        this.insightCardRepo = insightCardRepo;
        this.reportRepo      = reportRepo;
        this.snapshotRepo    = snapshotRepo;
        this.openAiClient    = openAiClient;
        this.objectMapper    = objectMapper;
    }

    // ── Scheduled job — every Sunday at midnight ──────────────────────────────

    @Scheduled(cron = "0 0 0 * * SUN")
    public void generateWeeklyReports() {
        List<String> clients = snapshotRepo.findAllClientIds();
        System.out.printf("[ReportAgent] Generating weekly reports for %d tenant(s)%n",
                clients.size());

        for (String clientId : clients) {
            try {
                generateReport(clientId, "WEEKLY");
            } catch (Exception e) {
                System.out.printf("[ReportAgent] Failed for %s: %s%n", clientId, e.getMessage());
            }
        }
    }

    // ── Manual trigger (also called via API) ──────────────────────────────────

    /**
     * Generates a report for the given client and period type.
     * Can be called manually from the API for on-demand report generation.
     */
    public AgentReportEntity generateReport(String clientId, String periodType) {
        LocalDateTime since = "MONTHLY".equals(periodType)
                ? LocalDateTime.now().minusDays(30)
                : LocalDateTime.now().minusDays(7);

        // Load all non-expired insight cards from the period
        List<InsightCardEntity> cards = insightCardRepo
                .findByClientIdAndStatusNotOrderByGeneratedAtDesc(clientId, "EXPIRED")
                .stream()
                .filter(c -> c.getGeneratedAt() != null && c.getGeneratedAt().isAfter(since))
                .limit(MAX_CARDS_IN_REPORT)
                .toList();

        String periodLabel = buildPeriodLabel(periodType);
        String content;

        if (cards.isEmpty()) {
            content = "No insight cards were generated during " + periodLabel + ". " +
                      "This may indicate the data source was unavailable or no significant patterns were detected.";
        } else {
            content = synthesiseReport(clientId, periodLabel, cards, periodType);
        }

        AgentReportEntity report = new AgentReportEntity();
        report.setClientId(clientId);
        report.setPeriodType(periodType);
        report.setPeriodLabel(periodLabel);
        report.setContent(content);
        report.setInsightCount(cards.size());
        report.setGeneratedAt(LocalDateTime.now());

        AgentReportEntity saved = reportRepo.save(report);
        System.out.printf("[ReportAgent] %s report generated for %s (%d insights)%n",
                periodType, clientId, cards.size());
        return saved;
    }

    // ── LLM synthesis ─────────────────────────────────────────────────────────

    private String synthesiseReport(String clientId, String periodLabel,
                                     List<InsightCardEntity> cards, String periodType) {
        StringBuilder context = new StringBuilder();
        context.append("Insight cards from ").append(periodLabel).append(":\n\n");

        for (int i = 0; i < cards.size(); i++) {
            InsightCardEntity c = cards.get(i);
            context.append(i + 1).append(". [").append(c.getBadge()).append("] ")
                   .append(c.getTitle()).append("\n");
            if (c.getDescription() != null && !c.getDescription().isBlank()) {
                context.append("   ").append(c.getDescription()).append("\n");
            }
            context.append("   Status: ").append(c.getStatus())
                   .append(" | Agent: ").append(c.getAgentName() != null ? c.getAgentName() : "n/a")
                   .append("\n");
            String reasons = parseJsonArray(c.getReasons());
            if (!reasons.isBlank()) context.append("   Reasons: ").append(reasons).append("\n");
            context.append("\n");
        }

        String userPrompt =
                "Write an executive briefing for client '" + clientId + "' covering " + periodLabel + ".\n\n" +
                context + "\n" +
                "Structure the report as:\n" +
                "1. Executive Summary (2-3 sentences on the period's headline findings)\n" +
                "2. Key Risks & Alerts (from ALERT and RISK cards)\n" +
                "3. Opportunities (from OPPORTUNITY cards)\n" +
                "4. Data Quality & Other Findings (from INFO cards)\n" +
                "5. Recommended Actions (3-5 prioritised actions for the business)\n\n" +
                "Write in a professional analyst tone. Be specific — reference actual findings from the cards.\n" +
                "Respond with JSON: {\"report\": \"<full report text with section headers>\"}";

        try {
            String response = openAiClient.chat(
                    "You are a senior business analyst writing a weekly intelligence briefing. " +
                    "Write clearly, professionally, and specifically. No filler.",
                    userPrompt
            );
            JsonNode node = objectMapper.readTree(response);
            String report = node.path("report").asText("").trim();
            return report.isBlank() ? buildFallbackReport(cards, periodLabel) : report;
        } catch (Exception e) {
            System.out.printf("[ReportAgent] LLM synthesis failed: %s%n", e.getMessage());
            return buildFallbackReport(cards, periodLabel);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildPeriodLabel(String periodType) {
        LocalDate now = LocalDate.now();
        if ("MONTHLY".equals(periodType)) {
            return now.minusMonths(1).format(DateTimeFormatter.ofPattern("MMMM yyyy"));
        }
        LocalDate weekStart = now.minusDays(7);
        return "Week of " + weekStart.format(WEEK_FMT);
    }

    private String parseJsonArray(String json) {
        if (json == null || json.isBlank()) return "";
        try {
            JsonNode node = objectMapper.readTree(json);
            List<String> items = new java.util.ArrayList<>();
            for (JsonNode n : node) items.add(n.asText());
            return String.join("; ", items);
        } catch (Exception e) {
            return json;
        }
    }

    private String buildFallbackReport(List<InsightCardEntity> cards, String period) {
        StringBuilder sb = new StringBuilder();
        sb.append("Weekly Intelligence Briefing — ").append(period).append("\n\n");
        sb.append("Executive Summary\n");
        sb.append(cards.size()).append(" insight(s) were generated during this period.\n\n");

        long alerts  = cards.stream().filter(c -> "ALERT".equals(c.getBadge())).count();
        long risks   = cards.stream().filter(c -> "RISK".equals(c.getBadge())).count();
        long opps    = cards.stream().filter(c -> "OPPORTUNITY".equals(c.getBadge())).count();

        sb.append("Summary: ").append(alerts).append(" alert(s), ")
          .append(risks).append(" risk(s), ").append(opps).append(" opportunit(y/ies).\n\n");

        sb.append("Findings\n");
        for (InsightCardEntity c : cards) {
            sb.append("• [").append(c.getBadge()).append("] ").append(c.getTitle()).append("\n");
        }
        return sb.toString();
    }
}
