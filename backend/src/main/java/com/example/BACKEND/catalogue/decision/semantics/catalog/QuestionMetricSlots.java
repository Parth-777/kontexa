package com.example.BACKEND.catalogue.decision.semantics.catalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Metric-relevant phrases extracted from question structure.
 */
public record QuestionMetricSlots(
        String primaryPhrase,
        String secondaryPhrase,
        String fullQuestion,
        SlotKind kind
) {
    public enum SlotKind {
        GENERAL,
        RANKING_METRIC,
        AFFECT_OUTCOME,
        AFFECT_DRIVER,
        CORRELATION_A,
        CORRELATION_B,
        DRIVE_OUTCOME
    }

    public List<String> phrasesForPrimaryResolution() {
        List<String> phrases = new ArrayList<>();
        if (primaryPhrase != null && !primaryPhrase.isBlank()) phrases.add(primaryPhrase);
        if (fullQuestion != null && !fullQuestion.isBlank()) phrases.add(fullQuestion);
        return phrases;
    }

    public List<String> phrasesForSecondaryResolution() {
        List<String> phrases = new ArrayList<>();
        if (secondaryPhrase != null && !secondaryPhrase.isBlank()) phrases.add(secondaryPhrase);
        return phrases;
    }

    public List<String> allPhrases() {
        List<String> all = new ArrayList<>(phrasesForPrimaryResolution());
        for (String p : phrasesForSecondaryResolution()) {
            if (!all.contains(p)) all.add(p);
        }
        return all;
    }
}
