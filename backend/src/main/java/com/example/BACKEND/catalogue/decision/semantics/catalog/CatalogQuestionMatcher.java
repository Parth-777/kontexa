package com.example.BACKEND.catalogue.decision.semantics.catalog;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Matches natural-language question phrases to catalog entries via aliases, token overlap,
 * and stem similarity. Returns ranked candidates, not only a single winner.
 */
@Component
public class CatalogQuestionMatcher {

    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");
    private static final double ACCEPT_THRESHOLD = 0.35;

    public MatchResult bestMetric(String text, SemanticCatalog catalog) {
        return bestMetric(text, null, catalog);
    }

    public MatchResult bestDimension(String text, SemanticCatalog catalog) {
        return best(text, catalog.dimensions(), "dimension", null);
    }

    public MatchResult bestMetric(String text, String phraseHint, SemanticCatalog catalog) {
        List<MetricMatchCandidate> ranked = rankMetrics(text, phraseHint, catalog);
        if (ranked.isEmpty() || !ranked.getFirst().accepted()) {
            return MatchResult.unresolved("metric");
        }
        MetricMatchCandidate top = ranked.getFirst();
        return new MatchResult(top.columnName(), top.registryKey(), top.score(), "metric", top.label());
    }

    public MatchResult bestDimension(String text, String phraseHint, SemanticCatalog catalog) {
        return best(combine(text, phraseHint), catalog.dimensions(), "dimension", null);
    }

    /**
     * Rank all catalog metrics against question text and optional phrase hint.
     */
    public List<MetricMatchCandidate> rankMetrics(String text, String phraseHint, SemanticCatalog catalog) {
        if (catalog == null || catalog.metrics() == null || catalog.metrics().isEmpty()) {
            return List.of();
        }
        String combined = (phraseHint != null && !phraseHint.isBlank())
                ? phraseHint
                : (text != null ? text : "");
        String normText = normalize(combined);
        Set<String> textTokens = stemmedTokens(normText);

        Map<String, MetricMatchCandidate> byColumn = new LinkedHashMap<>();
        for (SemanticCatalogEntry entry : catalog.metrics()) {
            ScoredHit hit = scoreEntry(normText, textTokens, entry);
            if (hit.score() <= 0) continue;
            MetricMatchCandidate candidate = new MetricMatchCandidate(
                    entry.columnName(),
                    entry.registryKey(),
                    entry.label(),
                    hit.score(),
                    hit.matchedPhrase(),
                    hit.matchKind(),
                    hit.score() >= ACCEPT_THRESHOLD);
            byColumn.merge(entry.columnName(), candidate, (a, b) -> a.score() >= b.score() ? a : b);
        }

        return byColumn.values().stream()
                .sorted(Comparator.comparingDouble(MetricMatchCandidate::score).reversed())
                .toList();
    }

    private MatchResult best(String text, List<SemanticCatalogEntry> entries, String kind, String hint) {
        if (text == null || text.isBlank() || entries == null || entries.isEmpty()) {
            return MatchResult.unresolved(kind);
        }
        String normText = normalize(combine(text, hint));
        Set<String> textTokens = stemmedTokens(normText);

        MatchResult best = MatchResult.unresolved(kind);
        for (SemanticCatalogEntry entry : entries) {
            ScoredHit hit = scoreEntry(normText, textTokens, entry);
            if (hit.score() > best.score()) {
                best = new MatchResult(entry.columnName(), entry.registryKey(), hit.score(), kind, entry.label());
            }
        }
        return best.score() >= ACCEPT_THRESHOLD ? best : MatchResult.unresolved(kind);
    }

    private ScoredHit scoreEntry(String normText, Set<String> textTokens, SemanticCatalogEntry entry) {
        double score = 0;
        String matched = null;
        String kind = null;

        List<String> phrases = phraseVariants(entry);
        for (String phrase : phrases) {
            String normPhrase = normalize(phrase);
            if (normPhrase.isBlank()) continue;

            if (normText.contains(normPhrase)) {
                double sub = normPhrase.contains(" ")
                        ? 0.94 + (normPhrase.length() / (double) Math.max(normText.length(), 1)) * 0.05
                        : 0.78 + (normPhrase.length() / (double) Math.max(normText.length(), 1)) * 0.08;
                if (normText.startsWith(normPhrase) || normText.startsWith(normPhrase + " ")) {
                    sub = Math.max(sub, 0.97);
                }
                if (sub > score) {
                    score = sub;
                    matched = phrase;
                    kind = "substring";
                }
            }

            Set<String> phraseTokens = stemmedTokens(normPhrase);
            if (!phraseTokens.isEmpty()) {
                long overlap = phraseTokens.stream().filter(textTokens::contains).count();
                double tokenScore = (double) overlap / phraseTokens.size();
                if (tokenScore >= 0.5) {
                    double s = 0.45 + tokenScore * 0.45;
                    if (s > score) {
                        score = s;
                        matched = phrase;
                        kind = "token_overlap";
                    }
                }
            }

            for (String qt : textTokens) {
                if (qt.length() < 4) continue;
                for (String pt : phraseTokens) {
                    if (pt.length() < 4) continue;
                    if (qt.startsWith(pt) || pt.startsWith(qt)) {
                        double s = 0.40 + Math.min(qt.length(), pt.length()) / 20.0;
                        if (s > score) {
                            score = s;
                            matched = phrase + "~" + qt;
                            kind = "stem_prefix";
                        }
                    }
                }
            }
        }

        return new ScoredHit(score * entry.rankScore(), matched, kind);
    }

    private List<String> phraseVariants(SemanticCatalogEntry entry) {
        Set<String> out = new LinkedHashSet<>();
        if (entry.aliases() != null) out.addAll(entry.aliases());
        out.addAll(phraseVariants(entry.columnName(), entry.label()));
        return new ArrayList<>(out);
    }

    static List<String> phraseVariants(String columnName, String label) {
        Set<String> out = new LinkedHashSet<>();
        if (columnName != null && !columnName.isBlank()) {
            out.add(columnName);
            out.add(columnName.replace('_', ' '));
            out.add(singularize(columnName.replace('_', ' ')));
        }
        if (label != null && !label.isBlank()) {
            out.add(label);
            out.add(singularize(label));
        }
        return new ArrayList<>(out);
    }

    private static String combine(String text, String hint) {
        if (hint == null || hint.isBlank()) return text != null ? text : "";
        if (text == null || text.isBlank()) return hint;
        return hint + " " + text;
    }

    static String normalize(String s) {
        if (s == null) return "";
        return NON_WORD.matcher(s.toLowerCase(Locale.ROOT).trim()).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    static Set<String> tokens(String normalized) {
        Set<String> out = new LinkedHashSet<>();
        if (normalized == null || normalized.isBlank()) return out;
        for (String t : normalized.split("\\s+")) {
            if (t.length() >= 2) out.add(t);
            String sing = singularizeToken(t);
            if (!sing.equals(t)) out.add(sing);
        }
        return out;
    }

    static Set<String> stemmedTokens(String normalized) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : tokens(normalized)) {
            out.add(t);
            out.add(MetricAliasGenerator.stemToken(t));
        }
        return out;
    }

    private static String singularize(String phrase) {
        if (phrase == null) return "";
        String[] parts = phrase.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(singularizeToken(parts[i]));
        }
        return sb.toString();
    }

    private static String singularizeToken(String token) {
        if (token == null || token.length() < 4) return token != null ? token : "";
        if (token.endsWith("ies")) return token.substring(0, token.length() - 3) + "y";
        if (token.endsWith("ses") || token.endsWith("xes") || token.endsWith("zes")) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("s") && !token.endsWith("ss")) return token.substring(0, token.length() - 1);
        return token;
    }

    private record ScoredHit(double score, String matchedPhrase, String matchKind) {}

    public record MatchResult(
            String columnName,
            String registryKey,
            double score,
            String kind,
            String label
    ) {
        public boolean resolved() {
            return columnName != null && !columnName.isBlank() && score > 0;
        }

        static MatchResult unresolved(String kind) {
            return new MatchResult(null, null, 0, kind, null);
        }
    }
}
