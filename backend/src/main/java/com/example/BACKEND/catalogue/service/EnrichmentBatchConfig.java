package com.example.BACKEND.catalogue.service;

/**
 * Tunables for token-budget batching of leaf-field semantic enrichment.
 * Field counts are never hardcoded — batches grow until estimated response
 * tokens approach {@link #responseBudgetFraction} of {@link #maxCompletionTokens}.
 */
public record EnrichmentBatchConfig(
        int maxCompletionTokens,
        /** Fraction of maxCompletionTokens reserved as safe response budget (0.4–0.6). */
        double responseBudgetFraction,
        /** Soft cap on prompt tokens for one batch (leaves room in context window). */
        int maxPromptTokensPerBatch,
        int maxRetriesPerBatch,
        /** Estimated completion tokens for one leaf's rich businessMeaning JSON entry. */
        int estimatedCompletionTokensPerLeaf,
        /** Fixed overhead (system + table header + JSON schema instructions). */
        int estimatedPromptOverheadTokens
) {
    public static EnrichmentBatchConfig defaults() {
        return new EnrichmentBatchConfig(
                16_384,
                0.50,
                12_000,
                2,
                420,
                1_800
        );
    }

    public int safeResponseTokenBudget() {
        return Math.max(512, (int) Math.floor(maxCompletionTokens * responseBudgetFraction));
    }
}
