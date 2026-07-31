package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.InsightOutput;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.PlannerRoutingOutcome;
import com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutiveConfidenceLabel;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutiveInsightCard;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Analyst-grade narrative and chart attachment derived only from {@link EvidencePack}.
 */
@Component
public class NarrativeRenderer {

    private final EvidencePackChartRecommender chartRecommender;
    private final EvidencePackValidator packValidator;

    public NarrativeRenderer(
            EvidencePackChartRecommender chartRecommender,
            EvidencePackValidator packValidator
    ) {
        this.chartRecommender = chartRecommender;
        this.packValidator = packValidator;
    }

    /** Test-friendly ctor without Spring. */
    public NarrativeRenderer() {
        this(new EvidencePackChartRecommender(), new EvidencePackValidator());
    }

    public NarrativeOutput render(EvidencePack pack) {
        String subject = subjectLabel(pack);
        String metric = metricPhrase(pack);
        List<EvidencePack.RequestResult> ok = succeeded(pack);
        List<EvidencePack.RequestResult> failed = failed(pack);

        List<String> keyFindings = new ArrayList<>();
        for (EvidencePack.RequestResult rr : ok) {
            String finding = analystFinding(rr, metric);
            if (!finding.isBlank()) {
                keyFindings.add(finding);
            }
        }
        for (EvidencePack.RequestResult rr : failed) {
            keyFindings.add("Evidence request '" + rr.requestId() + "' (" + rr.kind()
                    + ") failed" + (rr.error() != null ? ": " + rr.error() : "") + ".");
        }

        int planned = pack.coverage() != null ? pack.coverage().requestsPlanned() : 0;
        int succeeded = pack.coverage() != null ? pack.coverage().requestsSucceeded() : 0;
        int failedCount = pack.coverage() != null ? pack.coverage().requestsFailed() : 0;

        String headline = composeHeadline(pack, subject, metric, ok, succeeded, failedCount);
        String explanation = composeExplanation(
                pack, subject, metric, ok, keyFindings, planned, succeeded, failedCount);

        String confidence = pack.confidence() != null
                ? pack.confidence().level()
                : EvidencePack.Confidence.MODERATE;
        if (pack.insufficiency() != null && !pack.insufficiency().answerable()) {
            // Planner declared the causal claim unanswerable — do not present HIGH certainty.
            if (EvidencePack.Confidence.HIGH.equals(confidence)) {
                confidence = EvidencePack.Confidence.MODERATE;
            }
        }

        return new NarrativeOutput(headline, explanation, keyFindings, confidence);
    }

    public DecisionRunResult toResult(String runId, EvidencePack pack, NarrativeOutput narrative) {
        InsightOutput insight = new InsightOutput(
                runId,
                narrative.headline(),
                narrative.explanation(),
                List.of(),
                provenanceRefs(pack),
                List.of(),
                List.of(),
                List.of(),
                "Answer grounded in executed evidence requests.");

        double score = confidenceScore(narrative.confidenceLevel());
        List<EvidencePackChartRecommender.Recommendation> charts = chartRecommender.recommend(pack);
        ChartSpec primaryChart = charts.isEmpty() ? null : charts.getFirst().chart();

        ExecutiveInsightCard executiveCard = new ExecutiveInsightCard(
                narrative.headline(),
                narrative.explanation(),
                List.of(),
                primaryChart,
                narrative.headline(),
                ExecutiveConfidenceLabel.fromScore(score, false),
                String.join(" ", narrative.keyFindings() != null ? narrative.keyFindings() : List.of()));

        AnalyticalResponse analytical = new AnalyticalResponse(
                narrative.explanation(),
                List.of(),
                List.of(),
                primaryChart,
                new AnalyticalResponse.InsightBlock(narrative.headline(), narrative.explanation()),
                score);

        analytical = new AnalyticalResponse(
                analytical.executiveSummary(),
                analytical.findings(),
                analytical.metrics(),
                primaryChart,
                analytical.insight(),
                analytical.confidence(),
                analytical.evidencePanel(),
                analytical.assumption(),
                analytical.clarificationOptions(),
                analytical.recoveryMode(),
                analytical.availableMetrics(),
                analytical.suggestedReformulation(),
                analytical.recoveryReason(),
                executiveCard,
                analytical.exploratoryMode(),
                analytical.explorationNote(),
                analytical.confidenceTier(),
                analytical.executionMode(),
                analytical.executionTrace(),
                analytical.responseMode(),
                analytical.tableSpec(),
                "INVESTIGATION",
                analytical.correlationAnalysis());

        EvidencePackValidator.ValidationReport validation = packValidator.validate(pack);

        return new DecisionRunResult(
                insight,
                analytical,
                null,
                null,
                null,
                null,
                null,
                new PlannerRoutingOutcome(
                        "INVESTIGATION",
                        "investigation",
                        investigationPayload(pack, charts, validation)));
    }

    private Map<String, Object> investigationPayload(
            EvidencePack pack,
            List<EvidencePackChartRecommender.Recommendation> charts,
            EvidencePackValidator.ValidationReport validation
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", pack.question());
        if (pack.phenomenon() != null) {
            Map<String, Object> phenomenon = new LinkedHashMap<>();
            phenomenon.put("subject", pack.phenomenon().subject());
            if (pack.phenomenon().measure() != null) {
                phenomenon.put("measure", Map.of(
                        "column", pack.phenomenon().measure().column(),
                        "aggregation", pack.phenomenon().measure().aggregation()));
            }
            out.put("phenomenon", phenomenon);
        }
        if (pack.hypotheses() != null && !pack.hypotheses().isEmpty()) {
            out.put("hypotheses", pack.hypotheses().stream().map(h -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", h.id());
                row.put("claim", h.claim());
                row.put("status", h.status());
                return row;
            }).toList());
        }
        if (pack.insufficiency() != null) {
            Map<String, Object> insuff = new LinkedHashMap<>();
            insuff.put("answerable", pack.insufficiency().answerable());
            insuff.put("unknowns", pack.insufficiency().unknowns());
            insuff.put("blocked_reasons", pack.insufficiency().blockedReasons());
            out.put("insufficiency", insuff);
        }
        if (pack.requestResults() != null) {
            out.put("evidence_requests", pack.requestResults().stream().map(rr -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("request_id", rr.requestId());
                row.put("kind", rr.kind());
                row.put("role", rr.role());
                row.put("hypothesis_id", rr.hypothesisId());
                row.put("evidence_purpose", rr.evidencePurpose());
                row.put("ok", rr.ok());
                row.put("error", rr.error());
                row.put("query_ids", rr.queryIds());
                row.put("row_count", rr.rows() != null ? rr.rows().size() : 0);
                row.put("rows", rr.rows() != null ? rr.rows() : List.of());
                if (rr.sql() != null && !rr.sql().isBlank()) {
                    row.put("sql", rr.sql());
                }
                return row;
            }).toList());
        }
        if (pack.coverage() != null) {
            out.put("coverage", Map.of(
                    "requests_planned", pack.coverage().requestsPlanned(),
                    "requests_succeeded", pack.coverage().requestsSucceeded(),
                    "requests_failed", pack.coverage().requestsFailed()));
        }
        if (pack.confidence() != null) {
            out.put("confidence", pack.confidence().level());
            out.put("confidence_reasons", pack.confidence().reasons());
        }
        out.put("table_ref", pack.tableRef());
        out.put("charts", chartRecommender.toPayload(charts));
        out.put("evidence_validation", packValidator.toMap(validation));
        return out;
    }

    private String composeHeadline(
            EvidencePack pack,
            String subject,
            String metric,
            List<EvidencePack.RequestResult> ok,
            int succeeded,
            int failedCount
    ) {
        InvestigationPlan.Insufficiency insufficiency = pack.insufficiency();
        if (insufficiency != null && !insufficiency.answerable()) {
            return "Insufficient catalogue evidence to fully explain " + subject + ".";
        }
        if (succeeded == 0 && failedCount > 0) {
            return "Insufficient evidence to answer " + subject + " — all warehouse requests failed.";
        }
        if (succeeded == 0) {
            return "No warehouse evidence was collected for " + subject + ".";
        }

        EvidencePack.RequestResult focal = byRole(ok, "FOCAL");
        EvidencePack.RequestResult baseline = byRole(ok, "BASELINE");
        if (focal != null && baseline != null && hasValue(focal) && hasValue(baseline)) {
            double fv = valueOf(focal);
            double bv = valueOf(baseline);
            double delta = fv - bv;
            String direction = delta > 0 ? "higher" : (delta < 0 ? "lower" : "similar");
            return metric + " is " + fmt(fv) + " in the focal slice versus " + fmt(bv)
                    + " in the baseline (" + direction + " by " + fmt(Math.abs(delta)) + ").";
        }

        EvidencePack.RequestResult corr = byKind(ok, "correlation");
        if (corr != null && first(corr) != null && first(corr).containsKey("correlation")) {
            double r = number(first(corr).get("correlation"));
            return metric + " shows a " + correlationStrength(r) + " "
                    + (r >= 0 ? "positive" : "negative")
                    + " association (r=" + fmt(r) + ").";
        }

        EvidencePack.RequestResult conc = byKind(ok, "concentration");
        if (conc != null && first(conc) != null && first(conc).containsKey("member")) {
            Map<String, Object> top = first(conc);
            StringBuilder hb = new StringBuilder(metric + " is concentrated: top member "
                    + top.get("member"));
            if (top.containsKey("share")) {
                hb.append(" accounts for ").append(fmtPct(shareAsPercent(top.get("share"))));
            }
            Double cum80 = findCumulativeNear(conc, 0.8);
            if (cum80 != null) {
                hb.append("; cumulative share reaches ~80% among the ranked members");
            }
            hb.append(".");
            return hb.toString();
        }

        EvidencePack.RequestResult ranked = firstOfKinds(ok, "rank", "top_n", "share");
        if (ranked != null && first(ranked) != null && first(ranked).containsKey("member")) {
            Map<String, Object> top = first(ranked);
            StringBuilder hb = new StringBuilder("Highest " + metric + ": " + top.get("member"));
            if (top.containsKey("value")) {
                hb.append(" (").append(fmt(number(top.get("value")))).append(")");
            }
            if (top.containsKey("share")) {
                hb.append(", ").append(fmtPct(shareAsPercent(top.get("share")))).append(" of total");
            }
            hb.append(".");
            return hb.toString();
        }

        EvidencePack.RequestResult ts = byKind(ok, "time_series");
        if (ts != null && ts.rows() != null && ts.rows().size() >= 2) {
            Map<String, Object> firstRow = ts.rows().getFirst();
            Map<String, Object> lastRow = ts.rows().get(ts.rows().size() - 1);
            double start = number(firstRow.get("value"));
            double end = number(lastRow.get("value"));
            double delta = end - start;
            String direction = delta > 0 ? "increased" : (delta < 0 ? "decreased" : "was flat");
            return metric + " " + direction + " from " + fmt(start) + " to " + fmt(end)
                    + " across " + ts.rows().size() + " periods.";
        }

        return "Evidence for " + subject + " supports an answer based on "
                + succeeded + " warehouse request" + (succeeded == 1 ? "" : "s") + ".";
    }

    private String composeExplanation(
            EvidencePack pack,
            String subject,
            String metric,
            List<EvidencePack.RequestResult> ok,
            List<String> keyFindings,
            int planned,
            int succeeded,
            int failedCount
    ) {
        List<String> parts = new ArrayList<>();
        if (pack.question() != null && !pack.question().isBlank()) {
            parts.add("Question asked: " + pack.question().trim());
        }

        appendInsufficiency(parts, pack.insufficiency());
        appendHypotheses(parts, pack);
        parts.add("Metric under investigation: " + metric + ".");

        EvidencePack.RequestResult focal = byRole(ok, "FOCAL");
        EvidencePack.RequestResult baseline = byRole(ok, "BASELINE");
        if (focal != null && baseline != null && hasValue(focal) && hasValue(baseline)) {
            parts.add("Comparing the focal slice (" + fmt(valueOf(focal)) + ") against the baseline slice ("
                    + fmt(valueOf(baseline)) + ").");
            if (pack.insufficiency() == null || pack.insufficiency().answerable()) {
                parts.add(businessInterpretationComparison(metric, valueOf(focal), valueOf(baseline)));
            }
        }

        EvidencePack.RequestResult corr = byKind(ok, "correlation");
        if (corr != null && first(corr) != null && first(corr).containsKey("correlation")) {
            double r = number(first(corr).get("correlation"));
            parts.add(businessInterpretationCorrelation(metric, r, first(corr).get("n")));
        }

        EvidencePack.RequestResult conc = byKind(ok, "concentration");
        if (conc != null) {
            parts.add(businessInterpretationConcentration(metric, conc));
        }

        EvidencePack.RequestResult ts = byKind(ok, "time_series");
        if (ts != null && ts.rows() != null && ts.rows().size() >= 2) {
            parts.add(businessInterpretationTrend(metric, ts));
        }

        appendHypothesisEvidenceLinks(parts, pack, ok);

        for (String finding : keyFindings) {
            if (!finding.isBlank() && parts.stream().noneMatch(p -> p.contains(finding))) {
                parts.add(finding);
            }
        }

        parts.add(confidenceAndLimitations(pack, planned, succeeded, failedCount));
        return String.join(" ", parts);
    }

    private void appendInsufficiency(List<String> parts, InvestigationPlan.Insufficiency insufficiency) {
        if (insufficiency == null) {
            return;
        }
        if (!insufficiency.answerable()) {
            parts.add("Planner honesty: the approved catalogue cannot fully prove a causal explanation.");
            if (!insufficiency.blockedReasons().isEmpty()) {
                parts.add("Blocked: " + String.join("; ", insufficiency.blockedReasons()) + ".");
            }
            if (!insufficiency.unknowns().isEmpty()) {
                parts.add("Unknowns: " + String.join("; ", insufficiency.unknowns()) + ".");
            }
            parts.add("Any warehouse results below bound the problem; they do not close the causal claim.");
            return;
        }
        if (!insufficiency.unknowns().isEmpty() || !insufficiency.blockedReasons().isEmpty()) {
            if (!insufficiency.unknowns().isEmpty()) {
                parts.add("Catalogue gaps (unknowns): " + String.join("; ", insufficiency.unknowns()) + ".");
            }
            if (!insufficiency.blockedReasons().isEmpty()) {
                parts.add("Limitations: " + String.join("; ", insufficiency.blockedReasons()) + ".");
            }
        }
    }

    private void appendHypotheses(List<String> parts, EvidencePack pack) {
        List<InvestigationPlan.Hypothesis> hypotheses = pack.hypotheses();
        if (hypotheses == null || hypotheses.isEmpty()) {
            return;
        }
        parts.add("Competing hypotheses considered:");
        for (InvestigationPlan.Hypothesis h : hypotheses) {
            String status = h.status() != null ? h.status() : InvestigationPlan.Hypothesis.ALTERNATIVE;
            parts.add(h.id() + " (" + status + "): " + h.claim() + ".");
            if (InvestigationPlan.Hypothesis.REJECTED_CANDIDATE.equalsIgnoreCase(status)) {
                parts.add(h.id() + " is untestable with the approved catalogue — not warehouse-disproven.");
            }
        }
    }

    /**
     * Links warehouse outcomes to planner hypotheses without inventing support.
     * A hypothesis is mentioned as supported/weakened only when a succeeded request
     * with rows is linked via {@code hypothesisId}.
     */
    private void appendHypothesisEvidenceLinks(
            List<String> parts,
            EvidencePack pack,
            List<EvidencePack.RequestResult> ok
    ) {
        List<InvestigationPlan.Hypothesis> hypotheses = pack.hypotheses();
        if (hypotheses == null || hypotheses.isEmpty()) {
            return;
        }
        for (InvestigationPlan.Hypothesis h : hypotheses) {
            if (InvestigationPlan.Hypothesis.REJECTED_CANDIDATE.equalsIgnoreCase(h.status())) {
                continue;
            }
            List<EvidencePack.RequestResult> linked = ok.stream()
                    .filter(rr -> h.id() != null && h.id().equalsIgnoreCase(rr.hypothesisId()))
                    .filter(rr -> rr.rows() != null && !rr.rows().isEmpty())
                    .toList();
            if (linked.isEmpty()) {
                parts.add("No warehouse evidence was linked to " + h.id()
                        + " — the claim is unsupported by executed requests.");
                continue;
            }
            List<String> purposes = linked.stream()
                    .map(EvidencePack.RequestResult::evidencePurpose)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            String purposeLabel = purposes.isEmpty()
                    ? "linked warehouse evidence"
                    : String.join("/", purposes) + " evidence";
            parts.add(h.id() + " has " + purposeLabel + " from "
                    + linked.size() + " succeeded request"
                    + (linked.size() == 1 ? "" : "s")
                    + " (warehouse rows only — not causal proof).");
        }
    }

    private String analystFinding(EvidencePack.RequestResult rr, String metric) {
        String prefix = evidencePurposePrefix(rr);
        if (rr.rows() == null || rr.rows().isEmpty()) {
            return prefix + "Request " + rr.requestId() + " returned no rows for " + metric + ".";
        }
        Map<String, Object> first = rr.rows().getFirst();
        String kind = rr.kind() != null ? rr.kind().toLowerCase(Locale.ROOT) : "";

        String body = switch (kind) {
            case "correlation" -> {
                double r = number(first.get("correlation"));
                yield "Association strength for " + metric + ": r=" + fmt(r)
                        + " (" + correlationStrength(r) + ")"
                        + (first.containsKey("n") ? ", n=" + fmt(number(first.get("n"))) : "") + ".";
            }
            case "time_series" -> {
                Map<String, Object> last = rr.rows().get(rr.rows().size() - 1);
                yield metric + " moved from " + Objects.toString(first.get("period"), "?")
                        + " (" + fmt(number(first.get("value"))) + ") to "
                        + Objects.toString(last.get("period"), "?")
                        + " (" + fmt(number(last.get("value"))) + ") over "
                        + rr.rows().size() + " periods.";
            }
            case "concentration" -> {
                StringBuilder b = new StringBuilder("Concentration of " + metric + ": ");
                int limit = Math.min(3, rr.rows().size());
                for (int i = 0; i < limit; i++) {
                    Map<String, Object> row = rr.rows().get(i);
                    if (i > 0) {
                        b.append("; ");
                    }
                    b.append(row.getOrDefault("member", "?"))
                            .append(" share ").append(fmtPct(shareAsPercent(row.get("share"))));
                    if (row.containsKey("cumulative_share")) {
                        b.append(", cumulative ").append(fmtPct(shareAsPercent(row.get("cumulative_share"))));
                    }
                }
                b.append(".");
                yield b.toString();
            }
            case "rank", "top_n", "bottom_n", "share", "metric_by_dimension", "distribution" -> {
                StringBuilder b = new StringBuilder("Leading members for " + metric + ": ");
                int limit = Math.min(3, rr.rows().size());
                for (int i = 0; i < limit; i++) {
                    Map<String, Object> row = rr.rows().get(i);
                    if (i > 0) {
                        b.append("; ");
                    }
                    b.append(row.getOrDefault("member", "?"));
                    if (row.containsKey("value")) {
                        b.append("=").append(fmt(number(row.get("value"))));
                    } else if (row.containsKey("count")) {
                        b.append(" count=").append(fmt(number(row.get("count"))));
                    }
                    if (row.containsKey("focal_value") || row.containsKey("baseline_value")) {
                        b.append(" (focal ").append(fmt(number(row.get("focal_value"))))
                                .append(" vs baseline ").append(fmt(number(row.get("baseline_value")))).append(")");
                    }
                    if (row.containsKey("share")) {
                        b.append(" (").append(fmtPct(shareAsPercent(row.get("share")))).append(")");
                    }
                }
                b.append(".");
                yield b.toString();
            }
            case "slice_metric" -> {
                String role = rr.role() != null ? rr.role().toLowerCase(Locale.ROOT) + " " : "";
                yield "Measured " + role + metric + " = " + fmt(number(first.get("value"))) + ".";
            }
            default -> "Evidence " + rr.requestId() + " returned " + rr.rows().size() + " rows for " + metric + ".";
        };
        return prefix + body;
    }

    private static String evidencePurposePrefix(EvidencePack.RequestResult rr) {
        if (rr.evidencePurpose() == null || rr.evidencePurpose().isBlank()) {
            return "";
        }
        String purpose = rr.evidencePurpose().trim().toUpperCase(Locale.ROOT);
        String link = rr.hypothesisId() != null && !rr.hypothesisId().isBlank()
                ? " [" + rr.hypothesisId() + "]"
                : "";
        return switch (purpose) {
            case InvestigationPlan.EvidenceRequest.PURPOSE_PRIMARY -> "Primary evidence" + link + ": ";
            case InvestigationPlan.EvidenceRequest.PURPOSE_VALIDATION -> "Validation evidence" + link + ": ";
            case InvestigationPlan.EvidenceRequest.PURPOSE_COMPARISON -> "Comparison evidence" + link + ": ";
            case InvestigationPlan.EvidenceRequest.PURPOSE_CONTRADICTION -> "Contradiction evidence" + link + ": ";
            case InvestigationPlan.EvidenceRequest.PURPOSE_CONTEXT -> "Context evidence" + link + ": ";
            default -> "";
        };
    }

    private String businessInterpretationComparison(String metric, double focal, double baseline) {
        double delta = focal - baseline;
        if (Math.abs(delta) < 1e-9) {
            return "Business reading: the two slices look similar on " + metric + "; the apparent difference may not be meaningful.";
        }
        if (baseline == 0.0 && focal > 0) {
            return "Business reading: baseline " + metric
                    + " is effectively zero in the data (common when a channel does not capture the measure, e.g. cash tips). "
                    + "Treat the gap as a measurement/process difference as much as a behavioral one.";
        }
        double pct = baseline != 0 ? (delta / Math.abs(baseline)) * 100.0 : 0.0;
        return "Business reading: the focal slice differs from baseline by "
                + fmt(Math.abs(delta))
                + (baseline != 0 ? " (" + fmt(Math.abs(pct)) + "% relative)" : "")
                + " on " + metric + ".";
    }

    private String businessInterpretationCorrelation(String metric, double r, Object n) {
        String sample = n != null ? " based on n=" + fmt(number(n)) : "";
        if (Math.abs(r) < 0.2) {
            return "Business reading: little linear relationship for " + metric + sample
                    + "; other drivers likely dominate.";
        }
        return "Business reading: a " + correlationStrength(r) + " "
                + (r >= 0 ? "positive" : "negative")
                + " link for " + metric + sample
                + ". Correlation is not causation — validate with segment and temporal evidence.";
    }

    private String businessInterpretationConcentration(String metric, EvidencePack.RequestResult conc) {
        if (conc.rows() == null || conc.rows().isEmpty()) {
            return "";
        }
        Map<String, Object> top = conc.rows().getFirst();
        return "Business reading: " + metric + " appears skewed toward "
                + top.getOrDefault("member", "a few members")
                + (top.containsKey("share")
                ? " (" + fmtPct(shareAsPercent(top.get("share"))) + " share)"
                : "")
                + ". Concentration risk or opportunity depends on whether those members are stable.";
    }

    private String businessInterpretationTrend(String metric, EvidencePack.RequestResult ts) {
        Map<String, Object> first = ts.rows().getFirst();
        Map<String, Object> last = ts.rows().get(ts.rows().size() - 1);
        double start = number(first.get("value"));
        double end = number(last.get("value"));
        String direction = end > start ? "upward" : (end < start ? "downward" : "flat");
        return "Business reading: " + metric + " shows an overall " + direction
                + " trajectory across the observed window.";
    }

    private String confidenceAndLimitations(
            EvidencePack pack,
            int planned,
            int succeeded,
            int failedCount
    ) {
        String level = pack.confidence() != null ? pack.confidence().level() : EvidencePack.Confidence.MODERATE;
        StringBuilder b = new StringBuilder("Confidence: ").append(level)
                .append(" (").append(succeeded).append("/").append(planned)
                .append(" evidence requests succeeded)");
        if (failedCount > 0) {
            b.append(". Limitation: ").append(failedCount)
                    .append(" request(s) failed, so the answer is incomplete.");
        }
        if (pack.confidence() != null && pack.confidence().reasons() != null
                && !pack.confidence().reasons().isEmpty()) {
            b.append(" Evidence notes: ").append(String.join("; ", pack.confidence().reasons())).append(".");
        }
        b.append(" This narrative uses only executed EvidencePack rows — it does not invent metrics.");
        return b.toString();
    }

    private String metricPhrase(EvidencePack pack) {
        if (pack.phenomenon() != null && pack.phenomenon().measure() != null
                && pack.phenomenon().measure().column() != null) {
            String col = pack.phenomenon().measure().column();
            String agg = pack.phenomenon().measure().aggregation();
            if (agg != null && !agg.isBlank()) {
                return humanAgg(agg) + " " + humanColumn(col);
            }
            return humanColumn(col);
        }
        return subjectLabel(pack);
    }

    private String humanAgg(String agg) {
        return switch (agg.toUpperCase(Locale.ROOT)) {
            case "AVG", "AVERAGE", "MEAN" -> "average";
            case "SUM" -> "total";
            case "COUNT" -> "count of";
            case "MAX" -> "maximum";
            case "MIN" -> "minimum";
            default -> agg.toLowerCase(Locale.ROOT);
        };
    }

    private String humanColumn(String col) {
        return col.replace('_', ' ');
    }

    private String subjectLabel(EvidencePack pack) {
        if (pack.phenomenon() != null && pack.phenomenon().subject() != null
                && !pack.phenomenon().subject().isBlank()) {
            return pack.phenomenon().subject();
        }
        if (pack.question() != null && !pack.question().isBlank()) {
            return pack.question();
        }
        return "the investigated phenomenon";
    }

    private String correlationStrength(double r) {
        double a = Math.abs(r);
        if (a >= 0.7) {
            return "strong";
        }
        if (a >= 0.4) {
            return "moderate";
        }
        if (a >= 0.2) {
            return "weak";
        }
        return "very weak";
    }

    private List<EvidencePack.RequestResult> succeeded(EvidencePack pack) {
        List<EvidencePack.RequestResult> out = new ArrayList<>();
        if (pack.requestResults() == null) {
            return out;
        }
        for (EvidencePack.RequestResult rr : pack.requestResults()) {
            if (rr != null && rr.ok()) {
                out.add(rr);
            }
        }
        return out;
    }

    private List<EvidencePack.RequestResult> failed(EvidencePack pack) {
        List<EvidencePack.RequestResult> out = new ArrayList<>();
        if (pack.requestResults() == null) {
            return out;
        }
        for (EvidencePack.RequestResult rr : pack.requestResults()) {
            if (rr != null && !rr.ok()) {
                out.add(rr);
            }
        }
        return out;
    }

    private EvidencePack.RequestResult byRole(List<EvidencePack.RequestResult> rows, String role) {
        for (EvidencePack.RequestResult rr : rows) {
            if (role.equalsIgnoreCase(rr.role())) {
                return rr;
            }
        }
        return null;
    }

    private EvidencePack.RequestResult byKind(List<EvidencePack.RequestResult> rows, String kind) {
        for (EvidencePack.RequestResult rr : rows) {
            if (kind.equalsIgnoreCase(rr.kind())) {
                return rr;
            }
        }
        return null;
    }

    private EvidencePack.RequestResult firstOfKinds(List<EvidencePack.RequestResult> rows, String... kinds) {
        for (String kind : kinds) {
            EvidencePack.RequestResult rr = byKind(rows, kind);
            if (rr != null) {
                return rr;
            }
        }
        return null;
    }

    private Map<String, Object> first(EvidencePack.RequestResult rr) {
        if (rr == null || rr.rows() == null || rr.rows().isEmpty()) {
            return null;
        }
        return rr.rows().getFirst();
    }

    private boolean hasValue(EvidencePack.RequestResult rr) {
        Map<String, Object> row = first(rr);
        return row != null && row.containsKey("value");
    }

    private double valueOf(EvidencePack.RequestResult rr) {
        return number(first(rr).get("value"));
    }

    private Double findCumulativeNear(EvidencePack.RequestResult rr, double target) {
        if (rr.rows() == null) {
            return null;
        }
        return rr.rows().stream()
                .filter(r -> r.containsKey("cumulative_share"))
                .map(r -> number(r.get("cumulative_share")))
                .filter(v -> v >= target)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private double shareAsPercent(Object share) {
        double v = number(share);
        return Math.abs(v) <= 1.0001 ? v * 100.0 : v;
    }

    private double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public DecisionRunResult routingUnavailable(String runId) {
        return insufficient(runId, "Investigation mode is not enabled on this environment.");
    }

    public DecisionRunResult insufficient(String runId, String reason) {
        InsightOutput insight = new InsightOutput(
                runId, "Investigation unavailable", reason,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                "Investigation preconditions not met.");
        AnalyticalResponse analytical = new AnalyticalResponse(
                reason, List.of(), List.of(), null,
                new AnalyticalResponse.InsightBlock("Investigation unavailable", reason), 0.0);
        return new DecisionRunResult(insight, analytical);
    }

    private List<String> provenanceRefs(EvidencePack pack) {
        List<String> refs = new ArrayList<>();
        if (pack.requestResults() != null) {
            for (EvidencePack.RequestResult rr : pack.requestResults()) {
                if (rr == null) {
                    continue;
                }
                refs.add("request:" + rr.requestId());
                if (rr.queryIds() != null) {
                    refs.addAll(rr.queryIds());
                }
            }
        }
        return refs;
    }

    private double confidenceScore(String level) {
        if (EvidencePack.Confidence.HIGH.equals(level)) {
            return 0.9;
        }
        if (EvidencePack.Confidence.MODERATE.equals(level)) {
            return 0.6;
        }
        return 0.3;
    }

    private String fmtPct(double v) {
        if (Double.isInfinite(v) || Double.isNaN(v)) {
            return "n/a";
        }
        return String.format(Locale.US, "%.1f%%", v);
    }

    private String fmt(double v) {
        if (Double.isInfinite(v) || Double.isNaN(v)) {
            return String.valueOf(v);
        }
        return String.format(Locale.US, "%,.2f", v);
    }
}
