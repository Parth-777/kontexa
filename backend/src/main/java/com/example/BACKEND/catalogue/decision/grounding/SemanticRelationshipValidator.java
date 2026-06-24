package com.example.BACKEND.catalogue.decision.grounding;

import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validates metric-dimension relationships and rejects semantically invalid narratives.
 */
@Component
public class SemanticRelationshipValidator {

    private static final Pattern DIMENSION_CONTRIBUTES = Pattern.compile(
            "(?i)(trip\\s*distance|distance|hour|zone|borough|vendor|payment|segment|category|channel|"
                    + "weekday|month|period|pickup|dropoff|location)\\s+(contributes?|produces?|generates?|drives?)\\s+"
                    + "(\\$|USD|revenue|fare|amount)?");

    private static final Pattern DIMENSION_AS_CURRENCY = Pattern.compile(
            "(?i)(trip\\s*distance|distance|hour|zone|borough)\\s+(=|is|at|of)\\s+\\$?\\d");

    private static final Pattern RAW_VALUE_DUMP = Pattern.compile(
            "(?i)^(\\w[\\w\\s]{0,30})\\s+(is|equals|=)\\s+[\\d$,.]+\\s*$");

    private final PresentationLabelResolver labels;

    public SemanticRelationshipValidator(PresentationLabelResolver labels) {
        this.labels = labels;
    }

    public ValidationResult validateFinding(AnalyticalFinding finding) {
        List<String> violations = new ArrayList<>();
        String correctedSummary = findingSummary(finding);

        switch (finding) {
            case ContributionFinding c -> {
                violations.addAll(validateContribution(c));
                correctedSummary = correctContributionSummary(c);
            }
            case RankingFinding r -> {
                violations.addAll(validateRanking(r));
                violations.addAll(validateAggregation(r.rankedEntities().size(), r.leaderValue()));
                correctedSummary = correctRankingSummary(r);
            }
            case ComparativeFinding c -> {
                violations.addAll(validateComparative(c));
                correctedSummary = correctComparativeSummary(c);
            }
            case EfficiencyFinding e -> violations.addAll(validateEfficiency(e));
            case TemporalPatternFinding t -> violations.addAll(validateTemporal(t));
            case CorrelationFinding c -> {
                if (c.sampleSize() < 10) {
                    violations.add("Correlation sample too small: " + c.sampleSize());
                }
                correctedSummary = c.executiveSummary();
            }
        }

        violations.addAll(validateNarrative(correctedSummary, finding));

        boolean valid = violations.isEmpty();
        return new ValidationResult(valid, violations, correctedSummary);
    }

    public List<String> validateNarrative(String text, AnalyticalFinding context) {
        List<String> violations = new ArrayList<>();
        if (text == null || text.isBlank()) {
            violations.add("Empty narrative");
            return violations;
        }

        if (DIMENSION_CONTRIBUTES.matcher(text).find()) {
            violations.add("Dimension narrated as value-producing metric");
        }
        if (DIMENSION_AS_CURRENCY.matcher(text).find()) {
            violations.add("Dimension assigned monetary value");
        }
        if (RAW_VALUE_DUMP.matcher(text.trim()).find()) {
            violations.add("Raw metric value without interpretation");
        }
        if (text.toLowerCase(Locale.ROOT).contains("flag.val")) {
            violations.add("Internal alias leaked into narrative");
        }

        if (context instanceof ContributionFinding c) {
            String dim = labels.resolveDimension(c.dimensionLabel()).toLowerCase(Locale.ROOT);
            String metric = labels.resolveMetric(c.metricLabel()).toLowerCase(Locale.ROOT);
            if (text.toLowerCase(Locale.ROOT).contains(dim + " contributes")
                    || text.toLowerCase(Locale.ROOT).startsWith(dim + " ")) {
                violations.add("Contribution framed with dimension as subject");
            }
            if (!text.toLowerCase(Locale.ROOT).contains(metric.toLowerCase(Locale.ROOT).split(" ")[0])
                    && violations.isEmpty()) {
                // Soft warning — metric should appear in contribution narrative
            }
        }
        return violations;
    }

    public boolean isMetricDimensionCompatible(String metricRaw, String dimensionRaw) {
        if (metricRaw == null || dimensionRaw == null) return false;
        if (metricRaw.equalsIgnoreCase(dimensionRaw)) return false;
        if (labels.isLikelyDimension(metricRaw) && !labels.isLikelyMetric(metricRaw)) return false;
        if (labels.isInternalKey(metricRaw) || labels.isInternalKey(dimensionRaw)) return false;
        return true;
    }

    public String correctContributionSummary(ContributionFinding c) {
        String metric = labels.resolveMetric(c.metricLabel());
        String dimension = labels.resolveDimension(c.dimensionLabel());
        String topSegment = labels.resolveSegment(c.topContributor());
        double share = c.topContributorSharePct();
        double top3 = c.concentrationRatio();

        if (top3 >= 70 || share >= 40) {
            return String.format(Locale.ROOT,
                    "%s is concentrated in %s %s (%.1f%% of total; top 3 = %.1f%%).",
                    metric, topSegment, dimension.toLowerCase(Locale.ROOT), share, top3);
        }
        if (c.giniCoefficient() > 0.5) {
            return String.format(Locale.ROOT,
                    "%s varies unevenly across %s — %s leads at %.1f%%.",
                    metric, dimension.toLowerCase(Locale.ROOT), topSegment, share);
        }
        return String.format(Locale.ROOT,
                "%s is distributed across %d %s groups — %s is the largest at %.1f%%.",
                metric, c.segments().size(), dimension.toLowerCase(Locale.ROOT), topSegment, share);
    }

    private List<String> validateContribution(ContributionFinding c) {
        List<String> violations = new ArrayList<>();
        if (!isMetricDimensionCompatible(c.metricLabel(), c.dimensionLabel())) {
            violations.add("Metric-dimension pair incompatible: "
                    + c.metricLabel() + " × " + c.dimensionLabel());
        }
        if (labels.isLikelyDimension(c.metricLabel()) && !labels.isLikelyMetric(c.metricLabel())) {
            violations.add("Metric label classified as dimension: " + c.metricLabel());
        }
        if (c.segments().isEmpty()) {
            violations.add("Contribution analysis has no segments");
        }
        if (c.topContributorSharePct() < 0 || c.topContributorSharePct() > 100) {
            violations.add("Invalid share percentage");
        }
        if (c.topContributorSharePct() >= 99.5 && c.segments().size() < 4) {
            violations.add("Near-100% contribution claim with insufficient buckets");
        }
        if (c.executiveSummary() != null
                && c.executiveSummary().toLowerCase(Locale.ROOT).contains("100%")
                && c.topContributorSharePct() < 99) {
            violations.add("Narrative claims 100% but computed share is " + c.topContributorSharePct());
        }
        violations.addAll(validateAggregation(c.segments().size(), c.segments().stream()
                .mapToDouble(ContributionFinding.Segment::value).sum()));
        double shareSum = c.segments().stream().mapToDouble(ContributionFinding.Segment::sharePct).sum();
        if (shareSum > 0 && (shareSum < 85 || shareSum > 115)) {
            violations.add("Segment shares do not sum to ~100%");
        }
        return violations;
    }

    public List<String> validateAggregation(int groupCount, double totalValue) {
        List<String> violations = new ArrayList<>();
        if (groupCount < 2) violations.add("Insufficient groups for aggregation");
        if (totalValue <= 0 || Double.isNaN(totalValue)) violations.add("Non-positive aggregate total");
        return violations;
    }

    public String correctRankingSummary(RankingFinding r) {
        if (r.rankedEntities().isEmpty()) return r.executiveSummary();
        String metric = labels.resolveMetric(r.metricLabel());
        String dimension = labels.resolveDimension(r.groupingLabel());
        String leader = labels.resolveSegment(r.rankedEntities().getFirst().name());
        return String.format(Locale.ROOT,
                "%s varies across %s ranges — %s leads (%.1fx vs average, %.1fx vs lowest).",
                metric,
                dimension.toLowerCase(Locale.ROOT),
                leader,
                r.rankedEntities().getFirst().multiplierVsAvg(),
                r.leaderToTailMultiple());
    }

    public String correctComparativeSummary(ComparativeFinding c) {
        String metric = labels.resolveMetric(c.metricLabel());
        String a = labels.resolveSegment(c.entityA());
        String b = labels.resolveSegment(c.entityB());
        return String.format(Locale.ROOT,
                "%s for %s is %.1fx %s for %s (%.1f%% gap).",
                metric, a, c.multiple(),
                c.direction().equals("B_LEADS") ? "below" : "above",
                b, Math.abs(c.deltaPct()));
    }

    private List<String> validateRanking(RankingFinding r) {
        List<String> violations = new ArrayList<>();
        if (labels.isLikelyDimension(r.metricLabel()) && !labels.isLikelyMetric(r.metricLabel())) {
            violations.add("Ranking metric appears to be a dimension: " + r.metricLabel());
        }
        if (r.groupingLabel() != null && r.metricLabel() != null
                && r.groupingLabel().equalsIgnoreCase(r.metricLabel())) {
            violations.add("Ranking metric and grouping dimension are identical");
        }
        return violations;
    }

    private List<String> validateComparative(ComparativeFinding c) {
        List<String> violations = new ArrayList<>();
        if (labels.isInternalKey(c.entityA()) || labels.isInternalKey(c.entityB())) {
            violations.add("Comparative entities use internal identifiers");
        }
        return violations;
    }

    private List<String> validateEfficiency(EfficiencyFinding e) {
        List<String> violations = new ArrayList<>();
        if (labels.isLikelyDimension(e.numeratorLabel()) && labels.isLikelyDimension(e.denominatorLabel())) {
            violations.add("Efficiency ratio uses dimensions as operands");
        }
        return violations;
    }

    private List<String> validateTemporal(TemporalPatternFinding t) {
        List<String> violations = new ArrayList<>();
        if (t.periods() == null || t.periods().size() < 2) {
            violations.add("Insufficient temporal periods");
        }
        return violations;
    }

    private String findingSummary(AnalyticalFinding f) {
        return switch (f) {
            case ContributionFinding c -> c.executiveSummary();
            case RankingFinding r -> r.executiveSummary();
            case EfficiencyFinding e -> e.executiveSummary();
            case TemporalPatternFinding t -> t.executiveSummary();
            case ComparativeFinding c -> c.executiveSummary();
            case CorrelationFinding c -> c.executiveSummary();
        };
    }

    public record ValidationResult(
            boolean valid,
            List<String> violations,
            String correctedSummary
    ) {}
}
