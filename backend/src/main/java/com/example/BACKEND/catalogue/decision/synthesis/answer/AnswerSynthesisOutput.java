package com.example.BACKEND.catalogue.decision.synthesis.answer;

import java.util.List;

/**
 * GPT answer synthesis result — narrative only; charts/tables come from materialization.
 */
public record AnswerSynthesisOutput(
        String executiveSummary,
        List<String> keyFindings,
        String confidenceExplanation,
        String suggestedVisualization,
        String answerType,
        List<String> followUpQuestions
) {
    public static AnswerSynthesisOutput empty() {
        return new AnswerSynthesisOutput("", List.of(), "", "NONE", "UNKNOWN", List.of());
    }

    public boolean hasContent() {
        return executiveSummary != null && !executiveSummary.isBlank();
    }

    public String primaryTakeaway() {
        if (executiveSummary != null && !executiveSummary.isBlank()) {
            return executiveSummary;
        }
        return keyFindings != null && !keyFindings.isEmpty() ? keyFindings.getFirst() : "";
    }
}
