package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.model.ColumnInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Packs leaf columns into batches using estimated token budgets
 * (prompt growth + expected rich completion), not a fixed field count.
 */
public final class EnrichmentBatchPlanner {

    private EnrichmentBatchPlanner() {}

    public static List<List<ColumnInfo>> planLeafBatches(
            List<ColumnInfo> leaves,
            EnrichmentBatchConfig config
    ) {
        List<List<ColumnInfo>> batches = new ArrayList<>();
        if (leaves == null || leaves.isEmpty()) {
            return batches;
        }

        int responseBudget = config.safeResponseTokenBudget();
        int promptBudget = config.maxPromptTokensPerBatch();

        List<ColumnInfo> current = new ArrayList<>();
        int currentPrompt = config.estimatedPromptOverheadTokens();
        int currentResponse = 0;

        for (ColumnInfo leaf : leaves) {
            int colPrompt = estimateColumnPromptTokens(leaf);
            int colResponse = estimateColumnCompletionTokens(leaf, config);

            boolean wouldExceed = !current.isEmpty()
                    && (currentResponse + colResponse > responseBudget
                    || currentPrompt + colPrompt > promptBudget);

            if (wouldExceed) {
                batches.add(List.copyOf(current));
                current = new ArrayList<>();
                currentPrompt = config.estimatedPromptOverheadTokens();
                currentResponse = 0;
            }

            // A single oversized leaf still gets its own batch (never drop fields).
            current.add(leaf);
            currentPrompt += colPrompt;
            currentResponse += colResponse;
        }

        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return batches;
    }

    /**
     * Split a batch that truncated/failed into two smaller batches for adaptive retry.
     */
    public static List<List<ColumnInfo>> splitBatch(List<ColumnInfo> batch) {
        if (batch == null || batch.isEmpty()) {
            return List.of();
        }
        if (batch.size() == 1) {
            return List.of(batch);
        }
        int mid = batch.size() / 2;
        return List.of(
                List.copyOf(batch.subList(0, mid)),
                List.copyOf(batch.subList(mid, batch.size()))
        );
    }

    static int estimateColumnPromptTokens(ColumnInfo col) {
        int chars = 32; // name + type framing
        if (col.getColumnName() != null) chars += col.getColumnName().length();
        if (col.getDataType() != null) chars += col.getDataType().length();
        if (col.getParentPath() != null) chars += col.getParentPath().length() + 8;
        if (col.getSampleValues() != null) {
            for (String s : col.getSampleValues()) {
                if (s != null) chars += s.length() + 2;
            }
            chars += 20;
        }
        if (col.getMinValue() != null) chars += col.getMinValue().length();
        if (col.getMaxValue() != null) chars += col.getMaxValue().length();
        if (col.getAvgValue() != null) chars += col.getAvgValue().length();
        if (col.isSkipped()) chars += 80;
        return Math.max(8, chars / 4);
    }

    static int estimateColumnCompletionTokens(ColumnInfo col, EnrichmentBatchConfig config) {
        // Rich businessMeaning dominates; longer samples slightly increase prose.
        int base = config.estimatedCompletionTokensPerLeaf();
        int sampleBonus = 0;
        if (col.getSampleValues() != null && !col.getSampleValues().isEmpty()) {
            int sampleChars = col.getSampleValues().stream()
                    .filter(s -> s != null)
                    .mapToInt(String::length)
                    .sum();
            sampleBonus = Math.min(80, sampleChars / 8);
        }
        return base + sampleBonus;
    }
}
