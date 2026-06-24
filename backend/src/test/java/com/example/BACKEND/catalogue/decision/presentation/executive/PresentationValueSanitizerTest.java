package com.example.BACKEND.catalogue.decision.presentation.executive;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationValueSanitizerTest {

    @Test
    void detectsUnavailableValues() {
        assertTrue(PresentationValueSanitizer.isUnavailable(Double.NaN));
        assertTrue(PresentationValueSanitizer.isUnavailable(Double.POSITIVE_INFINITY));
        assertTrue(PresentationValueSanitizer.isUnavailable(Double.NEGATIVE_INFINITY));
        assertTrue(PresentationValueSanitizer.isUnavailable((Double) null));
        assertFalse(PresentationValueSanitizer.isUnavailable(0.0));
        assertFalse(PresentationValueSanitizer.isUnavailable(42.5));
    }

    @Test
    void sanitizesDisplayTextForCardsAndTables() {
        assertEquals("—", PresentationValueSanitizer.sanitizeDisplayText("NaN"));
        assertEquals("—", PresentationValueSanitizer.sanitizeDisplayText("NaN%"));
        assertEquals("—", PresentationValueSanitizer.sanitizeDisplayText("Infinity"));
        assertEquals("—", PresentationValueSanitizer.sanitizeDisplayText("-Infinity"));
        assertEquals("—", PresentationValueSanitizer.sanitizeDisplayText("undefined"));
        assertEquals("42.5%", PresentationValueSanitizer.sanitizeDisplayText("42.5%"));
    }

    @Test
    void sanitizesDisplayTextForTooltips() {
        assertEquals("Not available", PresentationValueSanitizer.sanitizeDisplayText("NaN", true));
        assertEquals("Not available", PresentationValueSanitizer.sanitizeDisplayText(null, true));
    }

    @Test
    void sanitizesPresentationMapRecursively() {
        Map<String, Object> source = Map.of(
                "kpis", List.of(Map.of(
                        "label", "Contribution",
                        "formatted_value", "NaN%")),
                "insights", List.of("Contribution percentage unavailable because denominator could not be computed."));

        Map<String, Object> sanitized = PresentationValueSanitizer.sanitizePresentationMap(source);

        @SuppressWarnings("unchecked")
        Map<String, Object> kpi = ((List<Map<String, Object>>) sanitized.get("kpis")).getFirst();
        assertEquals("—", kpi.get("formatted_value"));
        assertFalse(sanitized.toString().toLowerCase().contains("nan"));
    }
}
