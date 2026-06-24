package com.example.BACKEND.catalogue.decision.synthesis.answer;

import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerSynthesisPromptTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void userPromptIncludesQuestionWarehouseRowsAndExecutivePresentationWhenPresent() {
        ExecutivePresentation presentation = ExecutivePresentationFactory.withStatistics(
                ExecutivePresentationFactory.create(
                        "RANKING",
                        List.of(),
                        new ExecutivePresentation.PresentationTable(
                                "Top operation cost",
                                List.of(new ExecutivePresentation.PresentationColumn("rank", "Rank", "number")),
                                List.of(Map.of("rank", "1"))),
                        List.of(),
                        new ExecutivePresentation.PresentationSummary(
                                "RANKING", 1, 1, "operation cost", "company", "Top operation cost", "BAR", List.of()),
                        List.of("Rank 1 leads the table"),
                        List.of(),
                        List.of("What drives the gap between rank 1 and 2?")),
                Map.of(
                        "rowCount", 3,
                        "leaderName", "Acme",
                        "leaderValue", 42.0,
                        "valueGap", 10.0));

        AnswerSynthesisInput input = new AnswerSynthesisInput(
                "Which companies have the highest cost?",
                "SELECT company_name, SUM(cost) FROM t GROUP BY 1",
                List.of(Map.of("company_name", "Acme", "operation_cost", 42)),
                new AnswerSynthesisInput.MetricMetadata("operation_cost", "Operation Cost", "SUM"),
                new AnswerSynthesisInput.DimensionMetadata("company_name", "Company"),
                0.77,
                new AnswerSynthesisInput.ExecutionMetadata("run-1", 1, "RANKING", true),
                null,
                presentation,
                null);

        String prompt = AnswerSynthesisPrompt.userPrompt(input, MAPPER);

        assertTrue(prompt.contains("Which companies have the highest cost?"));
        assertTrue(prompt.contains("Warehouse rows"));
        assertTrue(prompt.contains("Executive presentation"));
        assertTrue(prompt.contains("RANKING"));
        assertTrue(prompt.contains("insights"));
        assertTrue(prompt.contains("statistics"));
        assertTrue(prompt.contains("leaderName"));
    }
}
