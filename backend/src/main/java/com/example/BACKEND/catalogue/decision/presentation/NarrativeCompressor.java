package com.example.BACKEND.catalogue.decision.presentation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Strips analytical filler and compresses prose to executive-grade brevity.
 */
@Component
public class NarrativeCompressor {

    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("shift from .+ to .+ units", Pattern.CASE_INSENSITIVE),
            Pattern.compile("associated segment values?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("marking distinct bands?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("cannot infer", Pattern.CASE_INSENSITIVE),
            Pattern.compile("based on (?:available|provided) (?:data|observations)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("verified (?:evidence|observations)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("OBS-\\d+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\[(?:INF|HYP|BLOCKED)\\]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("it is worth noting", Pattern.CASE_INSENSITIVE),
            Pattern.compile("the analysis shows", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)(trip\\s*distance|distance|hour|zone)\\s+contributes?"),
            Pattern.compile("(?i)FLAG\\.VAL"),
            Pattern.compile("revenue impact", Pattern.CASE_INSENSITIVE),
            Pattern.compile("drives? growth", Pattern.CASE_INSENSITIVE),
            Pattern.compile("strategic opportunit", Pattern.CASE_INSENSITIVE),
            Pattern.compile("optimization potential", Pattern.CASE_INSENSITIVE),
            Pattern.compile("pricing strateg", Pattern.CASE_INSENSITIVE),
            Pattern.compile("customer preference", Pattern.CASE_INSENSITIVE),
            Pattern.compile("investigate pricing", Pattern.CASE_INSENSITIVE)
    );

    public String clean(String text) {
        if (text == null || text.isBlank()) return "";
        String cleaned = text;
        for (Pattern p : FORBIDDEN) cleaned = p.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();
        return cleaned;
    }

    public String compress(String narrative, int maxSentences) {
        if (maxSentences <= 0) return "";
        String cleaned = clean(narrative);
        if (cleaned.isBlank()) return "";

        String[] parts = cleaned.split("(?<=[.!?])\\s+");
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            String s = part.trim();
            if (s.isBlank() || isFiller(s)) continue;
            kept.add(s);
            if (kept.size() >= maxSentences) break;
        }
        return String.join(" ", kept);
    }

    public String executiveSummary(String narrative, String fallback) {
        String cleaned = clean(narrative);
        if (cleaned.isBlank()) return fallback != null ? fallback : "";

        int dot = cleaned.indexOf('.');
        if (dot > 20 && dot < 220) return cleaned.substring(0, dot + 1).trim();
        if (cleaned.length() <= 180) return cleaned;
        return cleaned.substring(0, 177).trim() + "…";
    }

    private boolean isFiller(String sentence) {
        String lower = sentence.toLowerCase(Locale.ROOT);
        return lower.contains("associated segment")
                || lower.contains("distinct band")
                || lower.startsWith("importantly")
                || lower.contains("insufficient data");
    }
}
