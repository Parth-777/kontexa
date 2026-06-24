package com.example.BACKEND.catalogue.decision.presentation;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * User-facing correlation analysis — distinct from segment comparison payloads.
 */
public record CorrelationAnalysisPayload(
        String title,
        String summary,
        double correlationCoefficient,
        long   sampleSize,
        String strength,
        String direction,
        String businessInterpretation,
        String sourceVariable,
        String targetVariable
) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("summary", summary);
        m.put("correlation_coefficient", correlationCoefficient);
        m.put("sample_size", sampleSize);
        m.put("strength", strength);
        m.put("direction", direction);
        m.put("business_interpretation", businessInterpretation);
        m.put("source_variable", sourceVariable);
        m.put("target_variable", targetVariable);
        return m;
    }

    public static String formatStrength(String strength) {
        if (strength == null || strength.isBlank()) return "Unknown";
        return strength.substring(0, 1).toUpperCase(Locale.ROOT) + strength.substring(1);
    }
}
