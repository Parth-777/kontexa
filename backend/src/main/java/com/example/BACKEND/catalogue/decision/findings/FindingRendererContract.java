package com.example.BACKEND.catalogue.decision.findings;

import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders a {@link StructuredFindingsBundle} into structured, token-efficient
 * prompt sections for the LLM synthesis layer.
 *
 * Contract:
 *   - Every section opens with a typed header: [CONTRIBUTION_BREAKDOWN], [RANKING], etc.
 *   - Numbers are pre-formatted with appropriate precision
 *   - Tables and ranked lists use consistent ASCII alignment
 *   - The LLM is instructed to narrate findings, NOT re-derive them
 *
 * The rendered output replaces flat OBS-x observation sections in the prompt.
 */
@Component
public class FindingRendererContract {

    private static final int MAX_ROWS_PER_TABLE = 12;

    /**
     * Render the full bundle into an ordered prompt section.
     * Primary finding type is rendered first, then remaining types in descending magnitude.
     */
    public String render(StructuredFindingsBundle bundle) {
        if (bundle == null || !bundle.hasStructuredFindings()) {
            return "[STRUCTURED_FINDINGS]\nNo structured findings available — narrate from raw evidence.\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("─────────────────────────────────────────────────────────────────\n");
        sb.append("STRUCTURED ANALYTICAL FINDINGS\n");
        sb.append("─────────────────────────────────────────────────────────────────\n\n");

        // Render primary finding type first
        renderByType(sb, bundle, bundle.primaryFindingType());

        // Render remaining types
        for (FindingType type : FindingType.values()) {
            if (type == bundle.primaryFindingType()) continue;
            renderByType(sb, bundle, type);
        }

        sb.append("\n─────────────────────────────────────────────────────────────────\n");
        sb.append("PRESENTATION RULES:\n");
        sb.append("  1. Open with a direct answer that includes a specific number from the tables above.\n");
        sb.append("  2. State the leader, the gap, and the concentration — all with exact figures.\n");
        sb.append("  3. One sentence of comparative context: how the primary entity compares to the field.\n");
        sb.append("  4. One sentence of business implication — direct, no hedging.\n");
        sb.append("  5. Never say: 'approximately', 'seems to', 'appears', 'cannot determine', 'OBS-N'.\n");
        sb.append("  6. Do not re-derive or re-rank. Present exactly what is in the tables above.\n");
        sb.append("─────────────────────────────────────────────────────────────────\n");

        return sb.toString();
    }

    private void renderByType(StringBuilder sb, StructuredFindingsBundle bundle, FindingType type) {
        switch (type) {
            case CONTRIBUTION_BREAKDOWN -> bundle.contributionFindings()
                    .forEach(f -> sb.append(renderContribution(f)).append("\n"));
            case RANKING -> bundle.rankingFindings()
                    .forEach(f -> sb.append(renderRanking(f)).append("\n"));
            case EFFICIENCY -> bundle.efficiencyFindings()
                    .forEach(f -> sb.append(renderEfficiency(f)).append("\n"));
            case TEMPORAL_PATTERN -> bundle.temporalFindings()
                    .forEach(f -> sb.append(renderTemporal(f)).append("\n"));
            case COMPARATIVE -> bundle.comparativeFindings()
                    .forEach(f -> sb.append(renderComparative(f)).append("\n"));
        }
    }

    // ─── Contribution ─────────────────────────────────────────────────────

    private String renderContribution(ContributionFinding f) {
        StringBuilder sb = new StringBuilder();
        sb.append("[CONTRIBUTION_BREAKDOWN] Metric=").append(f.metricLabel())
                .append(" | Dimension=").append(f.dimensionLabel()).append("\n");
        sb.append("  Summary: ").append(f.executiveSummary()).append("\n");
        sb.append("  Concentration: top-3 = ").append(fmt1(f.concentrationRatio()))
                .append("% | Gini = ").append(fmt2(f.giniCoefficient()))
                .append(" | Leader/Tail = ").append(fmt1(f.leaderToTailRatio())).append("x\n");
        sb.append("\n  ").append(padR("Segment", 24)).append(padL("Value", 14))
                .append(padL("Share%", 8)).append("  Tier\n");
        sb.append("  ").append("─".repeat(54)).append("\n");

        f.segments().stream().limit(MAX_ROWS_PER_TABLE).forEach(s ->
                sb.append("  ").append(padR(s.name(), 24))
                        .append(padL(fmt2(s.value()), 14))
                        .append(padL(fmt1(s.sharePct()) + "%", 8))
                        .append("  ").append(s.tier()).append("\n")
        );

        if (f.segments().size() > MAX_ROWS_PER_TABLE) {
            sb.append("  … and ").append(f.segments().size() - MAX_ROWS_PER_TABLE)
                    .append(" more segments\n");
        }
        return sb.toString();
    }

    // ─── Ranking ──────────────────────────────────────────────────────────

    private String renderRanking(RankingFinding f) {
        StringBuilder sb = new StringBuilder();
        sb.append("[RANKING] ").append(f.groupingLabel())
                .append(" by ").append(f.metricLabel()).append("\n");
        sb.append("  Summary: ").append(f.executiveSummary()).append("\n");
        sb.append("  Leader=").append(fmt2(f.leaderValue()))
                .append(" | Median=").append(fmt2(f.medianValue()))
                .append(" | Tail=").append(fmt2(f.tailValue()))
                .append(" | Spread=").append(fmt1(f.leaderToTailMultiple())).append("x\n");
        sb.append("\n  ").append(padR("Entity", 24)).append(padL("Value", 14))
                .append(padL("Rank", 6)).append(padL("Pctile", 8))
                .append(padL("vs.Avg", 8)).append("  Tier\n");
        sb.append("  ").append("─".repeat(64)).append("\n");

        f.rankedEntities().stream().limit(MAX_ROWS_PER_TABLE).forEach(e ->
                sb.append("  ").append(padR(e.name(), 24))
                        .append(padL(fmt2(e.value()), 14))
                        .append(padL("#" + e.rank(), 6))
                        .append(padL(fmt1(e.percentileRank()) + "p", 8))
                        .append(padL(fmt1(e.multiplierVsAvg()) + "x", 8))
                        .append("  ").append(e.tier()).append("\n")
        );

        if (f.rankedEntities().size() > MAX_ROWS_PER_TABLE) {
            sb.append("  … and ").append(f.rankedEntities().size() - MAX_ROWS_PER_TABLE)
                    .append(" more entities\n");
        }
        return sb.toString();
    }

    // ─── Efficiency ───────────────────────────────────────────────────────

    private String renderEfficiency(EfficiencyFinding f) {
        StringBuilder sb = new StringBuilder();
        sb.append("[EFFICIENCY] ").append(f.groupingLabel())
                .append(": ").append(f.numeratorLabel())
                .append(" per ").append(f.denominatorLabel()).append("\n");
        sb.append("  Summary: ").append(f.executiveSummary()).append("\n");
        sb.append("  Best=").append(fmt3(f.bestEfficiency()))
                .append(" | Avg=").append(fmt3(f.averageEfficiency()))
                .append(" | Worst=").append(fmt3(f.worstEfficiency()))
                .append(" | Spread=").append(fmt1(f.efficiencySpread())).append("x\n");
        sb.append("\n  ").append(padR("Entity", 24)).append(padL("Ratio", 10))
                .append(padL("Deviation", 12)).append("  Tier\n");
        sb.append("  ").append("─".repeat(52)).append("\n");

        f.entries().stream().limit(MAX_ROWS_PER_TABLE).forEach(e ->
                sb.append("  ").append(padR(e.name(), 24))
                        .append(padL(fmt3(e.efficiencyRatio()), 10))
                        .append(padL(signedPct(e.deviationFromMean()), 12))
                        .append("  ").append(e.tier()).append("\n")
        );
        return sb.toString();
    }

    // ─── Temporal ─────────────────────────────────────────────────────────

    private String renderTemporal(TemporalPatternFinding f) {
        StringBuilder sb = new StringBuilder();
        sb.append("[TEMPORAL_PATTERN] ").append(f.temporalDimension()).append("\n");
        sb.append("  Summary: ").append(f.executiveSummary()).append("\n");
        sb.append("  Peak='").append(f.peakPeriod()).append("' (").append(fmt2(f.peakValue())).append(")")
                .append(" | Trough='").append(f.troughPeriod()).append("' (").append(fmt2(f.troughValue())).append(")")
                .append(" | Volatility=").append(fmt2(f.volatility()))
                .append(" | Momentum=").append(f.momentum()).append("\n");

        if (!f.inflectionPoints().isEmpty()) {
            sb.append("  Inflections:\n");
            f.inflectionPoints().forEach(ip ->
                    sb.append("    ").append(ip.fromPeriod()).append(" → ").append(ip.toPeriod())
                            .append(": ").append(ip.direction())
                            .append(" ").append(signedPct(ip.changePct() / 100)).append("\n")
            );
        }

        sb.append("\n  ").append(padR("Period", 16)).append(padL("Value", 14))
                .append(padL("Rank", 6)).append("\n");
        sb.append("  ").append("─".repeat(38)).append("\n");

        f.periods().stream().limit(MAX_ROWS_PER_TABLE).forEach(p -> {
            String marker = p.isPeak() ? " ◆ PEAK" : p.isTrough() ? " ▼ TROUGH" : "";
            sb.append("  ").append(padR(p.label(), 16))
                    .append(padL(fmt2(p.value()), 14))
                    .append(padL("#" + p.rank(), 6))
                    .append(marker).append("\n");
        });
        return sb.toString();
    }

    // ─── Comparative ──────────────────────────────────────────────────────

    private String renderComparative(ComparativeFinding f) {
        return String.format(
                "[COMPARATIVE] %s\n  %s\n  %s = %.2f | %s = %.2f | Delta = %+.1f%% | Multiple = %.2fx\n",
                f.metricLabel(),
                f.executiveSummary(),
                f.entityA(), f.valueA(),
                f.entityB(), f.valueB(),
                f.deltaPct(), f.multiple()
        );
    }

    // ─── Format helpers ───────────────────────────────────────────────────

    private String fmt1(double v) { return String.format("%.1f", v); }
    private String fmt2(double v) { return String.format("%.2f", v); }
    private String fmt3(double v) { return String.format("%.3f", v); }

    private String signedPct(double v) {
        return (v >= 0 ? "+" : "") + String.format("%.1f%%", v * 100);
    }

    private String padR(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }

    private String padL(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        return " ".repeat(width - s.length()) + s;
    }
}
