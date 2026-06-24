package com.example.BACKEND.catalogue.decision.synthesis.answer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnswerGroundingVerifierTest {

    @Test
    void acceptsNumbersPresentInRows() {
        List<Map<String, Object>> rows = List.of(
                Map.of("region", "North", "total_revenue", 1_250_000.0),
                Map.of("region", "South", "total_revenue", 875_000.0));

        AnswerSynthesisOutput output = new AnswerSynthesisOutput(
                "North leads with 1250000 and South follows at 875000.",
                List.of(),
                "",
                "BAR",
                "GROUPED",
                List.of());

        assertDoesNotThrow(() -> AnswerGroundingVerifier.assertFullyGrounded(output, rows));
    }

    @Test
    void rejectsInventedTotals() {
        List<Map<String, Object>> rows = List.of(Map.of("total_revenue", 100.0));

        AnswerSynthesisOutput output = new AnswerSynthesisOutput(
                "Total revenue is 9,999,999.",
                List.of(),
                "",
                "NONE",
                "SCALAR",
                List.of());

        AssertionError err = assertThrows(AssertionError.class,
                () -> AnswerGroundingVerifier.assertFullyGrounded(output, rows));
        assertTrue(err.getMessage().contains("Ungrounded"));
    }
}
