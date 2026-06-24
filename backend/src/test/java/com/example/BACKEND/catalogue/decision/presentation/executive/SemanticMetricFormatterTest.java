package com.example.BACKEND.catalogue.decision.presentation.executive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SemanticMetricFormatterTest {

    private SemanticMetricFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new SemanticMetricFormatter();
    }

    @Test
    void formatsLargeCurrencyCompactly() {
        assertEquals("$42.0B", formatter.asCurrency(42_000_000_000.0));
        assertEquals("$1.56B", formatter.asCurrency(1_560_000_000.0));
        assertEquals("$24.0M", formatter.asCurrency(24_000_000.0));
        assertEquals("$4.5K", formatter.asCurrency(4_500.0));
    }

    @Test
    void formatsPercentages() {
        assertEquals("23.4%", formatter.formatPercentValue(0.234));
    }

    @Test
    void formatsPlainIntegers() {
        assertEquals("123", formatter.compactNumber(123));
    }

    @Test
    void neverUsesScientificNotation() {
        String formatted = formatter.asCurrency(4.2E10);
        assertFalse(formatted.toLowerCase().contains("e"));
        assertEquals("$42.0B", formatted);
    }

    @Test
    void detectsCurrencyColumns() {
        assertEquals("currency", formatter.detectFormat("total_revenue"));
        assertEquals("currency", formatter.detectFormat("operation_cost"));
    }

    @Test
    void formatsInvalidValuesAsDash() {
        assertEquals("—", formatter.asSharePct(Double.NaN));
        assertEquals("—", formatter.asSharePct(Double.POSITIVE_INFINITY));
        assertEquals("—", formatter.formatForDisplay(Double.NaN, "currency"));
        assertEquals("—", formatter.formatPercentValue(Double.NaN));
        assertEquals("—", formatter.compactNumber(Double.NaN));
    }
}
