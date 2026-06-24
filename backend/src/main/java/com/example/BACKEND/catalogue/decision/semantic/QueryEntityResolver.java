package com.example.BACKEND.catalogue.decision.semantic;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Classifies question entities as metric, dimension, temporal, filter, or derived metric.
 */
@Component
public class QueryEntityResolver {

    private final SemanticDictionary dictionary;

    public QueryEntityResolver(SemanticDictionary dictionary) {
        this.dictionary = dictionary;
    }

    public List<ResolvedEntity> resolveAll(String question) {
        return dictionary.matchAll(question);
    }

    public ResolvedEntity firstMetric(String question) {
        return resolveAll(question).stream()
                .filter(e -> e.kind() == EntityKind.METRIC || e.kind() == EntityKind.DERIVED_METRIC)
                .max((a, b) -> Double.compare(a.matchScore(), b.matchScore()))
                .orElse(null);
    }

    public ResolvedEntity firstDimension(String question) {
        return resolveAll(question).stream()
                .filter(e -> e.kind() == EntityKind.DIMENSION)
                .max((a, b) -> Double.compare(a.matchScore(), b.matchScore()))
                .orElse(null);
    }

    public ResolvedEntity firstTemporal(String question) {
        return resolveAll(question).stream()
                .filter(e -> e.kind() == EntityKind.TEMPORAL_DIMENSION)
                .findFirst()
                .orElse(null);
    }

    public List<ResolvedEntity> metrics(String question) {
        List<ResolvedEntity> out = new ArrayList<>();
        for (ResolvedEntity e : resolveAll(question)) {
            if (e.kind() == EntityKind.METRIC || e.kind() == EntityKind.DERIVED_METRIC) {
                out.add(e);
            }
        }
        return out;
    }

    public List<ResolvedEntity> dimensions(String question) {
        List<ResolvedEntity> out = new ArrayList<>();
        for (ResolvedEntity e : resolveAll(question)) {
            if (e.kind() == EntityKind.DIMENSION || e.kind() == EntityKind.TEMPORAL_DIMENSION) {
                out.add(e);
            }
        }
        return out;
    }

    public ResolvedEntity extractBetween(String question, String before, String after) {
        if (question == null) return null;
        String q = question.toLowerCase(Locale.ROOT);
        int start = q.indexOf(before.toLowerCase(Locale.ROOT));
        if (start < 0) return null;
        start += before.length();
        int end = after != null ? q.indexOf(after.toLowerCase(Locale.ROOT), start) : q.length();
        if (end < 0) end = q.length();
        String fragment = question.substring(start, Math.min(end, question.length())).trim();
        return matchFragment(fragment);
    }

    public ResolvedEntity matchFragment(String fragment) {
        if (fragment == null || fragment.isBlank()) return null;
        String norm = fragment.toLowerCase(Locale.ROOT).trim();
        String synthetic = " " + norm.replace('_', ' ') + " ";
        for (SemanticDictionary.DictionaryEntry e : dictionary.entries()) {
            String phrase = e.phrase().toLowerCase(Locale.ROOT);
            if (matchesPhrase(synthetic, phrase) || matchesPhrase(" " + norm + " ", phrase)) {
                return new ResolvedEntity(e.phrase(), e.columnKey(), e.label(), e.kind(), 0.88);
            }
        }
        if (norm.endsWith("s")) {
            return matchFragment(norm.substring(0, norm.length() - 1));
        }
        return null;
    }

    private boolean matchesPhrase(String paddedText, String phrase) {
        if (phrase.contains(" ")) {
            return paddedText.contains(" " + phrase + " ");
        }
        return java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(phrase) + "\\b")
                .matcher(paddedText).find();
    }
}
