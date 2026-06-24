package com.example.BACKEND.catalogue.decision.presentation.executive;

import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.reasoning.GroundedAnalyticalFinding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Caps narrative output: 1 headline, 2 supporting insights, 1 evidence sentence.
 * Deduplicates repeated statements across headline, summary, and chart caption.
 */
@Component
public class NarrativeCompressionLayer {

    private static final Pattern ROBOTIC = Pattern.compile(
            "(?i)\\boutperforms?\\b|\\bmaterially increases?\\b|\\bassociated segment\\b");

    private final HumanNarrativeFormatter human;

    public NarrativeCompressionLayer(HumanNarrativeFormatter human) {
        this.human = human;
    }

    public record CompressedNarrative(
            String headline,
            String executiveSummary,
            String keyTakeaway,
            String evidenceSentence,
            String chartTitle
    ) {}

    public CompressedNarrative compress(GroundedAnalyticalFinding primary, AnalyticalIntentType intent) {
        if (primary == null) {
            return new CompressedNarrative("", "", "", "", "Analysis");
        }

        String headline = sanitize(human.headline(primary, intent));
        List<String> support = human.supportingInsights(primary, intent).stream()
                .map(this::sanitize)
                .filter(s -> !s.isBlank() && !isDuplicate(s, headline))
                .limit(2)
                .toList();
        String rawEvidence = sanitize(human.evidenceSentence(primary, intent));
        final String evidence = (isDuplicate(rawEvidence, headline)
                || support.stream().anyMatch(s -> isDuplicate(rawEvidence, s)))
                ? "" : rawEvidence;

        String summary = String.join(" ", support).trim();
        String title = human.chartTitle(primary.finding());

        return new CompressedNarrative(headline, summary, headline, evidence, title);
    }

    private String sanitize(String text) {
        if (text == null) return "";
        String out = ROBOTIC.matcher(text).replaceAll("").replaceAll("\\s{2,}", " ").trim();
        return out;
    }

    private boolean isDuplicate(String candidate, String reference) {
        if (candidate.isBlank() || reference.isBlank()) return false;
        String a = normalize(candidate);
        String b = normalize(reference);
        if (a.equals(b)) return true;
        return a.contains(b) || b.contains(a);
    }

    private String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9%×$]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
