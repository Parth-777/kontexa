package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CatalogueFieldMatcherTest {

    @Test
    void findsNearestColumnByEditDistance() {
        var catalogue = SemanticCatalogueFactory.catalogueFrom(null, MetricResolutionTestSupport.oilBundle());

        CatalogueFieldMatcher.Match misspelled =
                CatalogueFieldMatcher.nearest("maintainence_cost", catalogue);
        assertEquals("maintenance_cost", misspelled.columnName());
        assertTrue(misspelled.editDistance() <= 3);

        CatalogueFieldMatcher.Match synonym =
                CatalogueFieldMatcher.nearest("carbon_emission_tons", catalogue);
        assertEquals("carbon_emission", synonym.columnName());
        assertTrue(CatalogueFieldMatcher.hasSemanticOverlap("carbon_emission_tons", "carbon_emission"));
    }
}
