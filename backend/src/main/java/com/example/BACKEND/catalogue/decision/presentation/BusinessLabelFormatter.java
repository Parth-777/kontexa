package com.example.BACKEND.catalogue.decision.presentation;

import com.example.BACKEND.catalogue.decision.presentation.executive.BusinessSemanticAliases;
import com.example.BACKEND.catalogue.decision.presentation.executive.HumanNarrativeFormatter;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalizes bucket labels, metric names, and dimensions for user-facing output.
 */
@Component
public class BusinessLabelFormatter {

    private static final Pattern RAW_BUCKET = Pattern.compile("^(\\d+)[_\\s-]+(\\d+\\+?)$");
    private static final Pattern SINGLE_PLUS = Pattern.compile("^(\\d+)\\+$");

    private final HumanNarrativeFormatter human;
    private final BusinessSemanticAliases aliases;

    public BusinessLabelFormatter(HumanNarrativeFormatter human, BusinessSemanticAliases aliases) {
        this.human = human;
        this.aliases = aliases;
    }

    public String formatBucket(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String normalized = human.formatBucketLabel(raw);
        if (!normalized.equals(raw)) return normalized;

        String s = raw.trim().replace('_', '-');
        java.util.regex.Matcher m = RAW_BUCKET.matcher(s);
        if (m.matches()) {
            return m.group(1) + "–" + m.group(2);
        }
        java.util.regex.Matcher plus = SINGLE_PLUS.matcher(s);
        if (plus.matches()) return plus.group(1) + "+";
        return s.replace('-', '–');
    }

    public String formatMetric(String columnKey) {
        if (columnKey == null) return "";
        return aliases.resolve(columnKey);
    }

    public String formatDimension(String columnKey, String label) {
        if (label != null && !label.isBlank()) return label;
        if (columnKey == null) return "";
        return aliases.resolve(columnKey);
    }

    public String formatSegmentPhrase(String bucket) {
        return human.formatBucketPhrase(bucket);
    }
}
