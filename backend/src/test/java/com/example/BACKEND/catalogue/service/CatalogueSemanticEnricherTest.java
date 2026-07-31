package com.example.BACKEND.catalogue.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogueSemanticEnricherTest {

    @Test
    void richBusinessMeaningThresholdPreservesCurriculumText() {
        String rich = "x".repeat(120);
        String shortGloss = "Total revenue for the period.";
        assertTrue(CatalogueSemanticEnricher.isRichBusinessMeaning(rich));
        assertFalse(CatalogueSemanticEnricher.isRichBusinessMeaning(shortGloss));
        assertFalse(CatalogueSemanticEnricher.isRichBusinessMeaning(null));
        assertFalse(CatalogueSemanticEnricher.isRichBusinessMeaning(""));
    }
}
