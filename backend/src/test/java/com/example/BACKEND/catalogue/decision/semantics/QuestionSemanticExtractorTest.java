package com.example.BACKEND.catalogue.decision.semantics;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuestionSemanticExtractorTest {

    private QuestionSemanticExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = MetricResolutionTestSupport.extractor();
    }

    @Test
    void tipContribution_extractsTipAndRevenue_notDistance() {
        QuestionSemantics s = extractor.extract(
                "How does tip amount contribute to revenue?",
                emptyBundle());
        assertEquals("tip_amount", s.primaryMetric());
        assertEquals("total_amount", s.targetMetric());
        assertNull(s.dimension());
        assertEquals(AnalyticalRelationship.SHARE_OF_TOTAL, s.relationship());
        assertFalse(s.extractedEntities().contains("trip_distance"));
    }

    @Test
    void weekendContribution_extractsWeekendDimension_notDistance() {
        QuestionSemantics s = extractor.extract(
                "How do weekend rides contribute to revenue?",
                emptyBundle());
        assertEquals("weekend_flag", s.dimension());
        assertEquals("total_amount", s.primaryMetric());
        assertNotEquals("trip_distance", s.dimension());
    }

    @Test
    void tripDistanceAffect_extractsDistanceDimension() {
        QuestionSemantics s = extractor.extract(
                "How does trip distance affect revenue?",
                emptyBundle());
        assertEquals("trip_distance", s.dimension());
        assertTrue(s.primaryMetric() != null && s.primaryMetric().contains("amount"));
    }

    private RegistryResolutionBundle emptyBundle() {
        return new RegistryResolutionBundle(List.of(), List.of(), List.of(), null);
    }
}
