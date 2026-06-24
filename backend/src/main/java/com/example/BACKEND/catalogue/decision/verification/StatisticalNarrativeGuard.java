package com.example.BACKEND.catalogue.decision.verification;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Blocks exaggerated narrative unless statistical thresholds pass.
 * LLM prose is never a source of truth — only validated stats unlock strong language.
 */
@Component
public class StatisticalNarrativeGuard {

    private static final double DOMINANCE_SHARE_THRESHOLD   = 35.0;
    private static final double DOMINANCE_MULTIPLE_THRESHOLD  = 2.5;
    private static final double STRONG_IMPACT_CV_THRESHOLD    = 0.25;
    private static final double MASSIVE_DIFF_MULTIPLE         = 4.0;

    private static final List<Pattern> STRONG_PHRASES = List.of(
            Pattern.compile("\\bdominates?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdominant\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bstrong impact\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmassive difference\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\boverwhelming\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bhighly concentrated\\b", Pattern.CASE_INSENSITIVE)
    );

    public record NarrativeStats(
            double leaderSharePct,
            double leaderToTailMultiple,
            double coefficientOfVariation,
            int    groupCount
    ) {}

    public record GuardResult(
            String  sanitizedText,
            boolean strongLanguageAllowed,
            List<String> suppressedPhrases
    ) {}

    public GuardResult guard(String narrative, NarrativeStats stats) {
        if (narrative == null || narrative.isBlank()) {
            return new GuardResult("", false, List.of());
        }
        boolean allowDominance = stats != null && (
                stats.leaderSharePct() >= DOMINANCE_SHARE_THRESHOLD
                        || stats.leaderToTailMultiple() >= DOMINANCE_MULTIPLE_THRESHOLD);
        boolean allowStrongImpact = stats != null && stats.coefficientOfVariation() >= STRONG_IMPACT_CV_THRESHOLD;
        boolean allowMassive = stats != null && stats.leaderToTailMultiple() >= MASSIVE_DIFF_MULTIPLE;

        String out = narrative;
        List<String> suppressed = new java.util.ArrayList<>();

        if (!allowDominance) {
            out = replacePhrase(out, "\\bdominates?\\b", "leads");
            out = replacePhrase(out, "\\bdominant\\b", "leading");
            suppressed.add("dominates");
        }
        if (!allowStrongImpact) {
            out = replacePhrase(out, "\\bstrong impact\\b", "measurable difference");
            suppressed.add("strong impact");
        }
        if (!allowMassive) {
            out = replacePhrase(out, "\\bmassive difference\\b", "notable gap");
            out = replacePhrase(out, "\\boverwhelming\\b", "pronounced");
            suppressed.add("massive difference");
        }
        if (!allowDominance) {
            out = replacePhrase(out, "\\bhighly concentrated\\b", "concentrated");
        }

        return new GuardResult(out.trim(), allowDominance && allowStrongImpact, suppressed);
    }

    public NarrativeStats statsFrom(AnalyticalVerificationEngine.VerificationReport report) {
        if (report == null) return new NarrativeStats(0, 1, 0, 0);
        return new NarrativeStats(
                report.leaderSharePct(),
                report.leaderToTailMultiple(),
                report.coefficientOfVariation(),
                report.groupCount()
        );
    }

    private String replacePhrase(String text, String regex, String replacement) {
        return text.replaceAll("(?i)" + regex, replacement);
    }
}
