package com.example.BACKEND.catalogue.decision.presentation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Strips vague executive prose and unsupported prescriptive language.
 */
@Component
public class FactualLanguageGuard {

    public static final double PRESCRIPTIVE_CONFIDENCE_THRESHOLD = 0.85;

    private static final List<Pattern> BANNED_VAGUE = List.of(
            Pattern.compile("revenue impact", Pattern.CASE_INSENSITIVE),
            Pattern.compile("drives? growth", Pattern.CASE_INSENSITIVE),
            Pattern.compile("strategic opportunit", Pattern.CASE_INSENSITIVE),
            Pattern.compile("customer preference", Pattern.CASE_INSENSITIVE),
            Pattern.compile("optimization potential", Pattern.CASE_INSENSITIVE),
            Pattern.compile("pricing strateg", Pattern.CASE_INSENSITIVE),
            Pattern.compile("investigate pricing", Pattern.CASE_INSENSITIVE),
            Pattern.compile("consider diversif", Pattern.CASE_INSENSITIVE),
            Pattern.compile("prioritis(e|ing)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("double down", Pattern.CASE_INSENSITIVE),
            Pattern.compile("capitalise on", Pattern.CASE_INSENSITIVE),
            Pattern.compile("intervene before", Pattern.CASE_INSENSITIVE),
            Pattern.compile("guides? (?:pricing|dispatch|capacity)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("dependency risk", Pattern.CASE_INSENSITIVE),
            Pattern.compile("leadership should", Pattern.CASE_INSENSITIVE),
            Pattern.compile("warrant(?:s)? operational review", Pattern.CASE_INSENSITIVE),
            Pattern.compile("business implication", Pattern.CASE_INSENSITIVE),
            Pattern.compile("next steps?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("outperforms?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("materially increases?", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> PRESCRIPTIVE = List.of(
            Pattern.compile("^(?:consider|investigate|prioritise|monitor|review|explore|implement)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("should (?:focus|invest|act|prioritise)", Pattern.CASE_INSENSITIVE)
    );

    public String sanitize(String text) {
        if (text == null || text.isBlank()) return "";
        String out = text;
        for (Pattern p : BANNED_VAGUE) out = p.matcher(out).replaceAll("");
        out = out.replaceAll("\\s{2,}", " ").trim();
        out = out.replaceAll("(?i)\\b(the|a)\\s+\\.", ".");
        return out;
    }

    public String sanitizeSentence(String sentence, boolean allowPrescriptive, double confidence) {
        if (sentence == null || sentence.isBlank()) return "";
        String s = sanitize(sentence);
        if (!allowPrescriptive || confidence < PRESCRIPTIVE_CONFIDENCE_THRESHOLD) {
            for (Pattern p : PRESCRIPTIVE) {
                if (p.matcher(s).find()) return "";
            }
        }
        return s;
    }

    public boolean containsBannedPhrase(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("revenue impact") || lower.contains("strategic opportunity")
                || lower.contains("optimization potential") || lower.contains("pricing strateg")
                || lower.contains("customer preference") || lower.contains("drives growth");
    }
}
