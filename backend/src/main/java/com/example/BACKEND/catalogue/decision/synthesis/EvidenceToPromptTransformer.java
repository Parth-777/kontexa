package com.example.BACKEND.catalogue.decision.synthesis;

import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult;
import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult.*;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.*;
import com.example.BACKEND.catalogue.decision.findings.FindingRendererContract;
import com.example.BACKEND.catalogue.decision.findings.StructuredFindingsBundle;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedGroupEntry;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import com.example.BACKEND.catalogue.decision.calibration.CalibrationResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.planning.EvidenceCoverageReport;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import com.example.BACKEND.catalogue.decision.playbooks.Playbook;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Transforms ranked evidence into a structured, token-efficient prompt segment.
 *
 * Responsibilities:
 *   - Present only the top-ranked evidence (token budget)
 *   - Include comparative contexts (period-over-period, peer, baseline)
 *   - Include investigation tree findings (which segments drove the signal)
 *   - NEVER include raw rows — only structured, aggregated facts
 *   - Provide explicit analytical framing to guide executive synthesis
 */
@Component
public class EvidenceToPromptTransformer {

    private static final int MAX_EVIDENCE_ITEMS  = 4;
    private static final int MAX_METRICS_PER_EV  = 6;
    private static final int MAX_COMPARISONS     = 5;
    private static final int MAX_TREE_NODES      = 3;

    private final FindingRendererContract findingRenderer;

    public EvidenceToPromptTransformer(FindingRendererContract findingRenderer) {
        this.findingRenderer = findingRenderer;
    }

    public String transform(
            List<RankedEvidence>  ranked,
            List<EvidenceObject>  rawEvidence,
            IntentResolution      intent
    ) {
        return transform(ranked, rawEvidence, intent,
                null, null, null, null, null, null, null, null);
    }

    public String transform(
            List<RankedEvidence>   ranked,
            List<EvidenceObject>   rawEvidence,
            IntentResolution       intent,
            Playbook               playbook,
            ConstitutionReview     constitution,
            CalibrationResult      calibration,
            InvestigationPlan      investigationPlan,
            EvidenceCoverageReport coverageReport,
            AnalyticalDepthResult  depthResult,
            ExecutionFindings      executionFindings
    ) {
        return transform(ranked, rawEvidence, intent, playbook, constitution, calibration,
                investigationPlan, coverageReport, depthResult, executionFindings, null);
    }

    public String transform(
            List<RankedEvidence>    ranked,
            List<EvidenceObject>    rawEvidence,
            IntentResolution        intent,
            Playbook                playbook,
            ConstitutionReview      constitution,
            CalibrationResult       calibration,
            InvestigationPlan       investigationPlan,
            EvidenceCoverageReport  coverageReport,
            AnalyticalDepthResult   depthResult,
            ExecutionFindings       executionFindings,
            StructuredFindingsBundle findingsBundle
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("─────────────────────────────────────────────────────────────────\n");
        sb.append("QUESTION: ").append(intent.question()).append("\n");
        sb.append("─────────────────────────────────────────────────────────────────\n\n");

        // Business context hints from the playbook (domain framing only, no internal metadata)
        if (playbook != null && !playbook.businessContextHints().isEmpty()) {
            sb.append("ANALYTICAL CONTEXT:\n");
            playbook.businessContextHints().forEach(h -> sb.append("  • ").append(h).append("\n"));
            sb.append("\n");
        }

        // ── Structured findings (primary intelligence — must be narrated directly) ──────────
        if (findingsBundle != null && findingsBundle.hasStructuredFindings()) {
            sb.append(findingRenderer.render(findingsBundle));
            sb.append("\n");
        }

        // Primary evidence sections (supplementary — only when structured findings are unavailable)
        int limit = Math.min(ranked.size(), MAX_EVIDENCE_ITEMS);
        for (int i = 0; i < limit; i++) {
            RankedEvidence re = ranked.get(i);
            EvidenceObject ev = findEvidence(rawEvidence, re.evidenceId());
            if (ev == null) continue;
            renderEvidence(sb, re, ev, i + 1);
        }

        // ── MATERIALIZED GROUPED EVIDENCE (highest priority) ─────────────
        // This is the direct output of plan-driven GROUP BY execution.
        // The LLM must reason from these pre-computed, ranked groups — NOT from raw stats.
        if (executionFindings != null
                && executionFindings.materializedResult() != null
                && executionFindings.materializedResult().hasContent()) {
            renderMaterializedResult(sb, executionFindings.materializedResult());
        }

        // Execution findings: entity-level computed discoveries
        if (executionFindings != null && executionFindings.hasContent()) {
            renderExecutionFindings(sb, executionFindings);
        }

        // Investigation plan: analytical framework the system used to reason
        if (investigationPlan != null) {
            renderInvestigationPlan(sb, investigationPlan);
        }

        // Analytical depth findings: structural statistics
        if (depthResult != null && depthResult.hasContent()) {
            renderDepthFindings(sb, depthResult);
        }

        // Constitution: labeled claims and reasoning constraints
        if (constitution != null) {
            renderConstitution(sb, constitution);
        }

        // Coverage report: evidence completeness signal
        if (coverageReport != null && !coverageReport.sufficientForSynthesis()) {
            sb.append("\nNOTE: ").append(coverageReport.synthesisGuidanceNote()).append("\n");
        }

        // Calibration: response mode and synthesis depth controls
        if (calibration != null) {
            renderCalibration(sb, calibration);
        }

        return sb.toString();
    }

    private void renderEvidence(StringBuilder sb, RankedEvidence re, EvidenceObject ev, int rank) {
        sb.append("───────────────────────────────────────────\n");
        sb.append(String.format("EVIDENCE #%d  |  Entity: %s  |  Materiality Score: %.3f  |  Confidence: %.0f%%\n",
                rank, ev.entityRef(), re.score(), ev.confidence() * 100));
        sb.append("───────────────────────────────────────────\n");

        // Metrics
        if (!ev.metrics().isEmpty()) {
            sb.append("METRICS:\n");
            ev.metrics().entrySet().stream().limit(MAX_METRICS_PER_EV)
                    .forEach(e -> sb.append("  ").append(shortKey(e.getKey()))
                            .append(": ").append(formatValue(e.getValue())).append("\n"));
        }

        // Comparative contexts (most material first)
        List<ComparativeContext> ctxs = ev.comparativeContexts().stream()
                .sorted((a, b) -> Double.compare(Math.abs(b.deltaPercent()), Math.abs(a.deltaPercent())))
                .limit(MAX_COMPARISONS)
                .toList();
        if (!ctxs.isEmpty()) {
            sb.append("\nCOMPARATIVE CONTEXT:\n");
            for (ComparativeContext ctx : ctxs) {
                sb.append(String.format("  [%s] %s: %s vs %s → %s %.1f%% (%s→%s)\n",
                        ctx.comparisonType().name().replace("_", " "),
                        shortKey(ctx.metricKey()),
                        formatValue(ctx.currentValue()),
                        formatValue(ctx.previousValue()),
                        ctx.directionLabel().toUpperCase(),
                        Math.abs(ctx.deltaPercent()),
                        ctx.referencePeriod(), ctx.currentPeriod()
                ));
            }
        }

        // Investigation tree — top signals only
        List<InvestigationNode> tree = ev.investigationTree();
        if (!tree.isEmpty()) {
            sb.append("\nINVESTIGATION FINDINGS:\n");
            tree.stream().limit(MAX_TREE_NODES).forEach(node -> {
                sb.append("  ▸ ").append(node.signal()).append("\n");
                sb.append("    → ").append(node.interpretation()).append("\n");
                node.children().stream().limit(2).forEach(child ->
                        sb.append("      ↳ ").append(child.signal())
                          .append(" — ").append(child.interpretation()).append("\n")
                );
            });
        }

        // Ranking feature vector summary
        sb.append("\nMATERIALITY FEATURES: ");
        appendTopFeatures(sb, re.featureVector());
        sb.append("\n\n");
    }

    private void renderMaterializedResult(StringBuilder sb, MaterializedQueryResult mr) {
        sb.append("\n─────────────────────────────────────────────────────────────────\n");
        sb.append("COMPUTED ANALYTICAL DATA\n");
        sb.append("─────────────────────────────────────────────────────────────────\n\n");

        if (mr.resultType() == com.example.BACKEND.catalogue.decision.execution.materialization.AnalyticalResultType.CORRELATION_RESULT
                && mr.correlation() != null) {
            var c = mr.correlation();
            sb.append("CORRELATION ANALYSIS\n");
            sb.append("─────────────────────────────────────────────\n");
            sb.append(String.format("Variables: %s ↔ %s\n", c.sourceVariable(), c.targetVariable()));
            sb.append(String.format("Correlation coefficient (r): %.4f\n", c.correlationCoefficient()));
            sb.append(String.format("Sample size: %d observations\n", c.sampleSize()));
            sb.append(String.format("Strength: %s %s correlation\n", c.strength(), c.direction()));
            sb.append(String.format("Interpretation: %s\n", c.interpretation()));
        } else if (mr.resultType() == com.example.BACKEND.catalogue.decision.execution.materialization.AnalyticalResultType.SCALAR_RESULT
                && mr.scalar() != null) {
            var s = mr.scalar();
            sb.append("SCALAR AGGREGATE\n");
            sb.append("─────────────────────────────────────────────\n");
            sb.append(String.format("%s: %.4f\n", s.metricLabel(), s.value()));
            if (s.supportingCount() != null) {
                sb.append(String.format("Based on %d rows\n", s.supportingCount()));
            }
        } else {
            MaterializedGrouping primary = mr.primaryGrouping();
            if (primary == null || !primary.hasData()) {
                sb.append("No grouped breakdown available.\n");
            } else {
                sb.append(String.format("PRIMARY GROUPING: [%s]  |  %d groups  |  grand total: %.2f  |  Gini=%.3f\n",
                        primary.spec().displayLabel(), primary.groupCount(),
                        primary.totalValueSum(), primary.giniConcentration()));
                sb.append("─────────────────────────────────────────────\n");
                sb.append(String.format("%-30s %10s %8s %8s %12s\n",
                        "GROUP", primary.spec().displayLabel().substring(0, Math.min(primary.spec().displayLabel().length(), 10)),
                        "SHARE%", "EFF", "vs.AVG"));

                primary.top(Math.min(20, primary.rankedEntries().size())).forEach(e ->
                        sb.append(String.format("  #%-3d %-26s %10.2f %7.1f%% %8.3f %8.1fx  [%s]\n",
                                e.rank(), truncate(e.entityKey(), 26),
                                e.totalValue(), e.sharePct(),
                                e.efficiencyRatio(), e.multiplierVsAvg(), e.tier()))
                );

                mr.groupings().stream()
                        .filter(g -> g != primary && g.hasData())
                        .limit(2)
                        .forEach(g -> {
                            sb.append(String.format("\nSECONDARY GROUPING: [%s] — top %d of %d groups:\n",
                                    g.spec().displayLabel(), Math.min(5, g.rankedEntries().size()), g.groupCount()));
                            g.top(5).forEach(e ->
                                    sb.append(String.format("  #%d %-28s %.2f (%s, %.1f%% share)\n",
                                            e.rank(), truncate(e.entityKey(), 28),
                                            e.totalValue(), e.tier(), e.sharePct()))
                            );
                        });
            }
        }

        if (!mr.findings().isEmpty()) {
            sb.append("\nMATERIALIZED FINDINGS (quantified — use these verbatim):\n");
            mr.findings().forEach(f -> sb.append("  ▸ ").append(f.findingText()).append("\n"));
        }
        sb.append("\n");
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private void renderExecutionFindings(StringBuilder sb, ExecutionFindings ef) {
        sb.append("\n─────────────────────────────────────────────────────────────────\n");
        sb.append("ENTITY RANKINGS AND COMPUTED METRICS\n");
        sb.append("─────────────────────────────────────────────────────────────────\n\n");

        // Structural findings first — the most important non-obvious discoveries
        if (!ef.findings().isEmpty()) {
            sb.append("KEY FINDINGS:\n");
            ef.findings().forEach(f -> {
                String flag = f.isNonObvious() ? "★ " : "  ";
                sb.append(flag).append(f.findingText()).append("\n");
            });
            sb.append("\n");
        }

        // Primary ranking
        if (!ef.primaryRanking().isEmpty()) {
            sb.append("PRIMARY RANKING (by total value):\n");
            ef.primaryRanking().stream().limit(8).forEach(r ->
                    sb.append(String.format("  #%d [%s] value=%.2f  avg=%.2f  %.1fx  pct=%.0f  tier=%s%n",
                            r.rank(), r.entityKey(), r.value(), r.peerAverage(),
                            r.multiplierVsAverage(), r.percentileRank(), r.tier())));
            sb.append("\n");
        }

        // Efficiency ranking (if different from primary — surfaces the gap)
        if (!ef.efficiencyRanking().isEmpty()) {
            sb.append("EFFICIENCY RANKING (by value-per-unit):\n");
            ef.efficiencyRanking().stream().limit(5).forEach(r ->
                    sb.append(String.format("  #%d [%s] %s=%.2f  avg=%.2f  %.1fx  tier=%s%n",
                            r.rank(), r.entityKey(), r.rankingDimension(),
                            r.value(), r.peerAverage(),
                            r.multiplierVsAverage(), r.tier())));
            sb.append("\n");
        }

        // Statistical context
        StatisticalContext sc = ef.statisticalContext();
        sb.append(String.format("STATISTICAL CONTEXT: %d entities analyzed, %d retained after " +
                        "significance filter (min sample=%d). %s%n%n",
                sc.totalEntitiesConstructed(), sc.entitiesAfterSignificanceFilter(),
                sc.minimumSampleUsed(), sc.coverageNote()));
    }

    private void renderDepthFindings(StringBuilder sb, AnalyticalDepthResult depth) {
        sb.append("\n─────────────────────────────────────────────────────────────────\n");
        sb.append("STRUCTURAL PATTERNS AND DISTRIBUTIONS\n");
        sb.append("─────────────────────────────────────────────────────────────────\n\n");

        // Non-obvious insights first — these are the headline depth findings
        if (!depth.nonObviousInsights().isEmpty()) {
            sb.append("KEY STRUCTURAL INSIGHTS (use these to anchor your narrative):\n");
            depth.nonObviousInsights().forEach(i -> sb.append("  ▸ ").append(i).append("\n"));
            sb.append("\n");
        }

        // Segmentation buckets — the "how behaviour differs across segments" layer
        if (!depth.segmentBuckets().isEmpty()) {
            sb.append("SEGMENTATION ANALYSIS:\n");
            depth.segmentBuckets().forEach(b ->
                    sb.append(String.format("  [%s] avg=%.2f  total=%.0f  share=%.1f%%  n=%d  character=%s%n",
                            b.bucketLabel(), b.avgValue(), b.totalValue(),
                            b.sharePercent(), b.count(), b.characterization())));
            sb.append("\n");
        }

        // Efficiency metrics — value per unit
        if (!depth.efficiencyMetrics().isEmpty()) {
            sb.append("EFFICIENCY METRICS (value-per-unit ratios):\n");
            depth.efficiencyMetrics().forEach(e ->
                    sb.append(String.format("  %s = %.2f  (tier: %s  percentile: %.0f)%n",
                            e.ratioLabel(), e.value(), e.tier(), e.percentile())));
            sb.append("\n");
        }

        // Distribution profile — concentration and skew
        if (depth.distributionProfile() != null) {
            DistributionProfile d = depth.distributionProfile();
            sb.append(String.format(
                    "DISTRIBUTION PROFILE (%s): mean=%.2f  median=%.2f  " +
                    "top10%%share=%.1f%%  concentration=%.2f  character=%s%n%n",
                    d.metricKey(), d.mean(), d.median(),
                    d.top10SharePercent(), d.concentrationIndex(), d.character()));
        }

        // Inflection points — threshold behaviour
        if (!depth.inflectionPoints().isEmpty()) {
            sb.append("INFLECTION POINTS (threshold behaviour detected):\n");
            depth.inflectionPoints().forEach(ip ->
                    sb.append(String.format(
                            "  Threshold: %.1f %s%n  Below: %s%n  Above: %s%n  Implication: %s%n%n",
                            ip.thresholdValue(), ip.dimensionKey(),
                            ip.behaviorBelow(), ip.behaviorAbove(), ip.implication())));
        }

        // Relationships — co-movement and elasticity
        if (!depth.relationships().isEmpty()) {
            sb.append("VARIABLE RELATIONSHIPS:\n");
            depth.relationships().forEach(r ->
                    sb.append(String.format("  %s vs %s: r=%.2f  direction=%s  — %s%n",
                            r.dim1(), r.dim2(), r.correlationCoefficient(),
                            r.direction(), r.characterization())));
            sb.append("\n");
        }

        // Composite scores — strategic tier breakdown
        if (!depth.compositeScores().isEmpty()) {
            sb.append("COMPOSITE STRATEGIC SCORES:\n");
            depth.compositeScores().stream().limit(5).forEach(c -> {
                sb.append(String.format("  [%s] entity=%s  score=%.2f  tier=%s%n",
                        c.tier(), c.entityKey(), c.compositeScore(), c.tier()));
                if (!c.strengths().isEmpty())
                    sb.append("    Strengths: ").append(String.join(", ", c.strengths())).append("\n");
                if (!c.weaknesses().isEmpty())
                    sb.append("    Weaknesses: ").append(String.join(", ", c.weaknesses())).append("\n");
            });
            sb.append("\n");
        }
    }

    private void renderInvestigationPlan(StringBuilder sb, InvestigationPlan plan) {
        // Provide only framing guidance — no internal plan metadata
        if (plan.comparativeFramework() != null
                && plan.comparativeFramework().framingGuidance() != null
                && !plan.comparativeFramework().framingGuidance().isBlank()) {
            sb.append("\nCOMPARATIVE FRAMING: ").append(plan.comparativeFramework().framingGuidance()).append("\n");
        }
        plan.metricRequirements().stream()
                .filter(r -> r.priority() == 1)
                .findFirst()
                .ifPresent(r -> sb.append("PRIMARY METRIC: ").append(r.metricKey())
                        .append(" — ").append(r.analyticalPurpose()).append("\n"));
    }

    private void renderConstitution(StringBuilder sb, ConstitutionReview review) {
        // ── Computed data points — presented as clean facts, zero epistemic labels ──
        boolean hasDataPoints = !review.observations().isEmpty()
                || !review.analyticalInferences().isEmpty();

        if (hasDataPoints) {
            sb.append("\n─────────────────────────────────────────────────────────────────\n");
            sb.append("COMPUTED DATA POINTS\n");
            sb.append("─────────────────────────────────────────────────────────────────\n");
            // Observations: plain facts, no [OBS-N] labels
            review.observations().forEach(c ->
                    sb.append("  • ").append(c.claimText()).append("\n"));
            // Inferences: also plain facts, no [INF] label
            review.analyticalInferences().forEach(c ->
                    sb.append("  • ").append(c.claimText()).append("\n"));
            sb.append("\n");
        }

        // ── Patterns worth investigating — no [HYP] label ──
        if (!review.hypotheses().isEmpty()) {
            sb.append("PATTERNS TO INVESTIGATE:\n");
            review.hypotheses().forEach(c ->
                    sb.append("  ? ").append(c.claimText()).append("\n"));
            sb.append("\n");
        }

        // ── Speculation prohibition — clean phrasing, no [BLOCKED] labels ──
        if (!review.filteredSpeculation().isEmpty()) {
            sb.append("Do not speculate about external factors, competition, macro events, or causes"
                    + " without direct numerical evidence in the data above.\n\n");
        }

        // ── Reasoning constraints — filter out any that reference internal OBS labels ──
        review.reasoningConstraints().stream()
                .filter(r -> !r.contains("[OBS") && !r.contains("OBSERVATIONS section")
                          && !r.contains("numbered observation") && !r.contains("OBS-")
                          && !r.contains("[INF]") && !r.contains("PROHIBITED TOPICS"))
                .map(r -> r.replace("Do NOT invent", "Do not fabricate")
                           .replace("DO NOT", "Do not")
                           .replace("NEVER", "Never"))
                .forEach(r -> sb.append("  • ").append(r).append("\n"));
    }

    private void renderCalibration(StringBuilder sb, CalibrationResult cal) {
        sb.append("\n─────────────────────────────────────────────────────────────────\n");
        sb.append("RESPONSE SCOPE\n");
        sb.append("─────────────────────────────────────────────────────────────────\n");
        sb.append("  narrative: REQUIRED (max ").append(cal.maxNarrativeSentences()).append(" sentences)\n");
        sb.append("  strategicImplications: ").append(cal.includeStrategicImpls()   ? "INCLUDE" : "OMIT → return []").append("\n");
        sb.append("  operationalRisks:      ").append(cal.includeOperationalRisks() ? "INCLUDE" : "OMIT → return []").append("\n");
        sb.append("  businessCauses:        ").append(cal.includeBusinessCauses()   ? "INCLUDE" : "OMIT → return []").append("\n");
        sb.append("  actions:               ").append(cal.includeActions()          ? "INCLUDE" : "OMIT → return []").append("\n");

        if (cal.directAnswerFirst()) {
            sb.append("\nOpen with a direct answer that includes a specific number or named entity.\n");
        }

        if (!cal.calibrationInstructions().isEmpty()) {
            sb.append("\n");
            cal.calibrationInstructions().forEach(instr ->
                    sb.append("  • ").append(instr).append("\n"));
        }
    }

    private void appendTopFeatures(StringBuilder sb, Map<String, Double> features) {
        features.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(4)
                .forEach(e -> sb.append(String.format("%s=%.2f  ", e.getKey(), e.getValue())));
    }

    private String shortKey(String key) {
        String[] parts = key.split("[.\\[\\]]");
        return parts[parts.length - 1].replace("_", " ");
    }

    private String formatValue(Object v) {
        if (v == null) return "—";
        if (v instanceof Double d) return String.format("%.2f", d);
        if (v instanceof Number n) return String.format("%.2f", n.doubleValue());
        try {
            double d = Double.parseDouble(v.toString());
            return String.format("%.2f", d);
        } catch (NumberFormatException e) {
            return v.toString();
        }
    }

    private EvidenceObject findEvidence(List<EvidenceObject> evidence, String evidenceId) {
        return evidence.stream()
                .filter(ev -> ev.evidenceId().equals(evidenceId))
                .findFirst().orElse(null);
    }
}
