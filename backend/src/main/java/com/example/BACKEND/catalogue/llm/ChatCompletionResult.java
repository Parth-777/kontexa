package com.example.BACKEND.catalogue.llm;

/**
 * Full chat-completion outcome for diagnostics (content + transport metadata).
 * Existing callers that only need text continue to use {@link OpenAiClient#chat}.
 */
public record ChatCompletionResult(
        boolean requestSent,
        int httpStatus,
        String model,
        int maxTokensRequested,
        String content,
        String finishReason,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        String rawResponseBody,
        String errorMessage
) {
    public boolean hasContent() {
        return content != null && !content.isBlank();
    }

    public boolean truncatedByLength() {
        return "length".equalsIgnoreCase(finishReason);
    }
}
