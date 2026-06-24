package com.example.BACKEND.catalogue.decision.synthesis.answer;

import com.example.BACKEND.catalogue.semantic.phase2.GptStructuredCompletionClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnswerSynthesizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void legacyModeSkipsSynthesis() {
        AnswerSynthesisProperties props = new AnswerSynthesisProperties();
        props.setMode(AnswerSynthesisProperties.Mode.legacy);

        GptAnswerSynthesizer gpt = new GptAnswerSynthesizer(
                mock(GptStructuredCompletionClient.class), MAPPER);
        AnswerSynthesizer synthesizer = new AnswerSynthesizer(props, gpt);

        Optional<AnswerSynthesisOutput> result = synthesizer.synthesize(sampleInput());

        assertTrue(result.isEmpty());
        assertEquals("legacy", synthesizer.modeName());
    }

    @Test
    void gptModeProducesSynthesisFromWarehouseRows() {
        String mockJson = """
                {
                  "executiveSummary": "North leads with $1.2M in total revenue.",
                  "keyFindings": ["North: $1.2M", "South: $0.8M"],
                  "confidenceExplanation": "Based on two warehouse rows.",
                  "suggestedVisualization": "BAR",
                  "answerType": "GROUPED"
                }
                """;

        GptStructuredCompletionClient client = mock(GptStructuredCompletionClient.class);
        when(client.completeStructured(anyString(), anyString(), any(), eq("warehouse_answer_synthesis")))
                .thenReturn(mockJson);

        AnswerSynthesisProperties props = new AnswerSynthesisProperties();
        props.setMode(AnswerSynthesisProperties.Mode.gpt);

        GptAnswerSynthesizer gpt = new GptAnswerSynthesizer(client, MAPPER);
        AnswerSynthesizer synthesizer = new AnswerSynthesizer(props, gpt);

        Optional<AnswerSynthesisOutput> result = synthesizer.synthesize(sampleInput());

        assertTrue(result.isPresent());
        AnswerSynthesisOutput output = result.get();
        assertTrue(output.executiveSummary().contains("North"));
        assertEquals(2, output.keyFindings().size());
        assertEquals("GROUPED", output.answerType());
        assertEquals("BAR", output.suggestedVisualization());
    }

    @Test
    void gptModeSkipsWhenWarehouseDidNotSucceed() {
        AnswerSynthesisProperties props = new AnswerSynthesisProperties();
        props.setMode(AnswerSynthesisProperties.Mode.gpt);

        GptAnswerSynthesizer gpt = new GptAnswerSynthesizer(
                mock(GptStructuredCompletionClient.class), MAPPER);
        AnswerSynthesizer synthesizer = new AnswerSynthesizer(props, gpt);

        AnswerSynthesisInput failed = new AnswerSynthesisInput(
                "How is revenue split?",
                "SELECT 1",
                List.of(Map.of("region", "North", "total_revenue", 100)),
                new AnswerSynthesisInput.MetricMetadata("total_revenue", "Revenue", "SUM"),
                new AnswerSynthesisInput.DimensionMetadata("region", "Region"),
                0.9,
                new AnswerSynthesisInput.ExecutionMetadata("run-1", 0, "NONE", false),
                null,
                null,
                null);

        assertTrue(synthesizer.synthesize(failed).isEmpty());
    }

    private static AnswerSynthesisInput sampleInput() {
        return new AnswerSynthesisInput(
                "How is total revenue split across regions?",
                "SELECT region, SUM(revenue) FROM t GROUP BY 1",
                List.of(
                        Map.of("region", "North", "total_revenue", 1_200_000),
                        Map.of("region", "South", "total_revenue", 800_000)),
                new AnswerSynthesisInput.MetricMetadata("total_revenue", "Total Revenue", "SUM"),
                new AnswerSynthesisInput.DimensionMetadata("region", "Region"),
                0.85,
                new AnswerSynthesisInput.ExecutionMetadata("run-1", 2, "GROUPED", true),
                null,
                null,
                null);
    }
}
