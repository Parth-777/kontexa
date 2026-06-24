package com.example.BACKEND.catalogue.decision.semantics;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticRegistry;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.planning.DomainAnalyticalDefaults;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetricResolutionEngineTest {

    private MetricResolutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = MetricResolutionTestSupport.engine();
    }

    @Test
    void rejectsDistanceSubstitutionForTipQuestion() {
        QuestionSemantics semantics = new QuestionSemantics(
                "How does tip amount contribute to revenue?",
                "tip_amount", "Tips", "total_amount", "Total Revenue",
                null, null, "composition",
                AnalyticalIntentType.CONTRIBUTION, AnalyticalRelationship.SHARE_OF_TOTAL,
                List.of(), 0.75, List.of("tip_amount", "total_amount"));

        MetricResolution r = engine.resolve(semantics, emptyBundle());
        assertTrue(r.isUsable());
        assertEquals("tip_amount", r.primaryMetric());
        assertFalse(r.rejected());
    }

    @Test
    void resolvesWeekendDimension() {
        QuestionSemantics semantics = new QuestionSemantics(
                "How do weekend rides contribute to revenue?",
                "total_amount", "Total Revenue", null, null,
                "weekend_flag", "Weekend", "weekend_flag",
                AnalyticalIntentType.CONTRIBUTION, AnalyticalRelationship.DIMENSION_BREAKDOWN,
                List.of(), 0.8, List.of("weekend_flag", "total_amount"));

        MetricResolution r = engine.resolve(semantics, emptyBundle());
        assertEquals("weekend_flag", r.dimension());
        assertNotEquals("trip_distance", r.dimension());
    }

    private RegistryResolutionBundle emptyBundle() {
        return new RegistryResolutionBundle(List.of(), List.of(), List.of(), null);
    }
}
