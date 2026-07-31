package com.example.BACKEND.catalogue.decision.clarification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticResolutionGptBypassTest {

    @Test
    void bypassedForGptPlanner_doesNotSelectLexicalMetricOrDimension() {
        SemanticResolution bypass = SemanticResolution.bypassedForGptPlanner(
                "Which traffic sources generate the most sessions?");

        assertNotNull(bypass);
        assertNull(bypass.investigation());
        assertTrue(bypass.metricResolution().rejected());
        assertNull(bypass.metricResolution().primaryMetric());
        assertNull(bypass.semantics().primaryMetric());
        assertNull(bypass.semantics().dimension());
        assertEquals("", bypass.resolved().assumption().primaryMetric());
        assertTrue(bypass.resolved().explorationNote().contains("Lexical stack bypassed"));
        assertTrue(bypass.analysisPlan().blockingReason().contains("unused in GPT mode"));
    }
}
