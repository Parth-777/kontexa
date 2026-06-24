package com.example.BACKEND.catalogue.decision.diagnostics;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticRegistry;
import com.example.BACKEND.catalogue.decision.planning.DomainAnalyticalDefaults;
import com.example.BACKEND.catalogue.decision.semantic.QueryEntityResolver;
import com.example.BACKEND.catalogue.decision.semantic.ResolvedEntity;
import com.example.BACKEND.catalogue.decision.semantic.SemanticDictionary;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.catalogue.decision.semantics.catalog.CatalogQuestionMatcher;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SchemaDrivenQuestionResolver;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticCatalog;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticCatalogBuilder;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticCatalogEntry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Metric resolution audit — run: mvn test -Dtest=MetricResolutionAuditTest
 */
class MetricResolutionAuditTest {

    private static final Pattern WHICH_HIGHEST = Pattern.compile(
            "(?i)which\\s+(.+?)\\s+(?:generates|generate|produces|produce|has|have|yields|yield)\\s+"
                    + "(?:the\\s+)?(?:highest|lowest|most|least|best|worst|maximum|minimum|max|min)\\s+(.+?)\\??$");
    private static final Pattern WHICH_ARE = Pattern.compile(
            "(?i)which\\s+(.+?)\\s+are\\s+(?:the\\s+)?(?:most|least\\s+)?(\\w+)\\??$");
    private static final Pattern WHICH_MOST_PROFITABLE = Pattern.compile(
            "(?i)which\\s+(.+?)\\s+is\\s+(?:the\\s+)?(?:most|least)\\s+(\\w+)\\??$");
    private static final Pattern AFFECT = Pattern.compile(
            "(?i)how\\s+(?:does|do)\\s+(.+?)\\s+(?:affect|impact|influence|drive)\\s+(.+?)\\??$");
    private static final Pattern CORRELATE = Pattern.compile(
            "(?i)(?:does|do)\\s+(.+?)\\s+correlat(?:e|es|ion)?\\s+with\\s+(.+?)\\??$");

    private static final SemanticDictionary DICTIONARY = new SemanticDictionary();
    private static final QueryEntityResolver ENTITY_RESOLVER = new QueryEntityResolver(DICTIONARY);
    private static final CatalogQuestionMatcher MATCHER = new CatalogQuestionMatcher();
    private static final SemanticCatalogBuilder CATALOG_BUILDER = new SemanticCatalogBuilder();
    private static final SchemaDrivenQuestionResolver SCHEMA_RESOLVER =
            new SchemaDrivenQuestionResolver(MATCHER);
    private static final QuestionSemanticExtractor EXTRACTOR = MetricResolutionTestSupport.extractor();
    private static final MetricResolutionEngine METRIC_ENGINE = MetricResolutionTestSupport.engine();

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("Which oil field generates the highest profit?"),
                Arguments.of("How does downtime affect profitability?"),
                Arguments.of("Does carbon emission correlate with profit margin?"),
                Arguments.of("What drives profitability?"),
                Arguments.of("Which facility type is most profitable?")
        );
    }

    @ParameterizedTest
    @MethodSource("cases")
    void auditMetricResolution(String question) {
        RegistryResolutionBundle bundle = oilBundle();
        SemanticCatalog catalog = CATALOG_BUILDER.build(bundle);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("QUESTION: " + question);
        System.out.println("=".repeat(80));

        System.out.println("\n[1] RAW EXTRACTED TERMS");
        printRegexExtractions(question);
        List<ResolvedEntity> dictMatches = ENTITY_RESOLVER.resolveAll(question);
        if (dictMatches.isEmpty()) {
            System.out.println("  dictionary matches: (none — SemanticDictionary is NYC-taxi only)");
        } else {
            for (ResolvedEntity e : dictMatches) {
                System.out.printf("  dictionary: phrase=%s → %s (%s) score=%.3f%n",
                        e.phrase(), e.columnKey(), e.kind(), e.matchScore());
            }
        }
        for (String frag : fragmentAttempts(question)) {
            ResolvedEntity m = ENTITY_RESOLVER.matchFragment(frag);
            System.out.printf("  matchFragment(%s) → %s%n", frag,
                    m != null ? m.columnKey() + " (" + m.kind() + ")" : "null");
        }

        var schema = SCHEMA_RESOLVER.resolve(question, catalog);
        System.out.println("  schema metricHint resolution: "
                + (schema.metricColumn() != null ? schema.metricColumn() : "UNRESOLVED")
                + " (schema intent=" + schema.intent() + ")");

        System.out.println("\n[2-4] CANDIDATE METRICS (full question text)");
        List<ScoredCandidate> fullQ = scoreAllMetrics(question, null, catalog);
        printCandidates(fullQ);

        String metricHint = metricHintFromQuestion(question);
        if (metricHint != null) {
            System.out.println("\n[2-4] CANDIDATE METRICS (metric hint=\"" + metricHint + "\")");
            List<ScoredCandidate> hinted = scoreAllMetrics(question, metricHint, catalog);
            printCandidates(hinted);
        }

        CatalogQuestionMatcher.MatchResult winner = metricHint != null
                ? MATCHER.bestMetric(question, metricHint, catalog)
                : MATCHER.bestMetric(question, catalog);
        System.out.println("\n  WINNER (CatalogQuestionMatcher.bestMetric): "
                + (winner.resolved() ? winner.columnName() + " score=" + winner.score() : "UNRESOLVED"));
        explainWinner(question, metricHint, catalog, winner, fullQ);

        System.out.println("\n[5] QuestionSemanticExtractor → MetricResolutionEngine");
        QuestionSemantics semantics = EXTRACTOR.extract(question, bundle);
        System.out.printf("  extractor primaryMetric=%s targetMetric=%s dimension=%s confidence=%.3f%n",
                semantics.primaryMetric(), semantics.targetMetric(),
                semantics.dimension(), semantics.confidence());
        MetricResolution resolution = METRIC_ENGINE.resolve(semantics, bundle);
        System.out.printf("  engine primaryMetric=%s targetMetric=%s rejected=%s reason=%s%n",
                resolution.primaryMetric(), resolution.targetMetric(),
                resolution.rejected(), blankToNone(resolution.rejectionReason()));
        System.out.printf("  engine isUsable=%s confidence=%.3f%n",
                resolution.isUsable(), resolution.confidence());

        System.out.println("\n[5] WHY profit/profitability → profit_margin");
        explainProfitMapping(question, metricHint, catalog, semantics, resolution);
    }

    private static void printRegexExtractions(String question) {
        Matcher h = WHICH_HIGHEST.matcher(question.trim());
        if (h.find()) {
            System.out.println("  WHICH_HIGHEST dimHint=\"" + h.group(1).trim()
                    + "\" metricHint=\"" + h.group(2).trim() + "\"");
        }
        Matcher are = WHICH_ARE.matcher(question.trim());
        if (are.find()) {
            System.out.println("  WHICH_ARE dimHint=\"" + are.group(1).trim()
                    + "\" adjHint=\"" + are.group(2).trim() + "\"");
        }
        Matcher prof = WHICH_MOST_PROFITABLE.matcher(question.trim());
        if (prof.find()) {
            System.out.println("  WHICH_IS_MOST dimHint=\"" + prof.group(1).trim()
                    + "\" adjHint=\"" + prof.group(2).trim() + "\"");
        }
        Matcher aff = AFFECT.matcher(question.trim());
        if (aff.find()) {
            System.out.println("  AFFECT driver=\"" + aff.group(1).trim()
                    + "\" outcome=\"" + aff.group(2).trim() + "\"");
        }
        Matcher corr = CORRELATE.matcher(question.trim());
        if (corr.find()) {
            System.out.println("  CORRELATE metricA=\"" + corr.group(1).trim()
                    + "\" metricB=\"" + corr.group(2).trim() + "\"");
        }
        if (question.toLowerCase(Locale.ROOT).contains("drive")) {
            System.out.println("  keyword: contains \"drive\" → extractor intent DISTRIBUTION");
        }
    }

    private static List<String> fragmentAttempts(String question) {
        List<String> frags = new ArrayList<>();
        String q = question.toLowerCase(Locale.ROOT);
        if (q.contains("profit")) frags.add("profit");
        if (q.contains("profitability")) frags.add("profitability");
        if (q.contains("profit margin")) frags.add("profit margin");
        if (q.contains("downtime")) frags.add("downtime");
        if (q.contains("carbon emission")) frags.add("carbon emission");
        if (q.contains("carbon")) frags.add("carbon");
        if (q.contains("facility type")) frags.add("facility type");
        if (q.contains("oil field")) frags.add("oil field");
        return frags;
    }

    private static String metricHintFromQuestion(String question) {
        Matcher h = WHICH_HIGHEST.matcher(question.trim());
        if (h.find()) return h.group(2).trim();
        Matcher are = WHICH_ARE.matcher(question.trim());
        if (are.find()) return are.group(2).trim();
        Matcher prof = WHICH_MOST_PROFITABLE.matcher(question.trim());
        if (prof.find()) return prof.group(2).trim();
        Matcher aff = AFFECT.matcher(question.trim());
        if (aff.find()) return aff.group(2).trim();
        Matcher corr = CORRELATE.matcher(question.trim());
        if (corr.find()) return corr.group(2).trim();
        if (question.toLowerCase(Locale.ROOT).contains("profitability")) return "profitability";
        if (question.toLowerCase(Locale.ROOT).contains("profitable")) return "profitable";
        return null;
    }

    /** Mirrors CatalogQuestionMatcher scoring — all candidates, not just winner. */
    private static List<ScoredCandidate> scoreAllMetrics(
            String text, String phraseHint, SemanticCatalog catalog
    ) {
        String combined = phraseHint != null && !phraseHint.isBlank()
                ? phraseHint + " " + text : text;
        String normText = normalize(combined);
        Set<String> textTokens = tokens(normText);

        List<ScoredCandidate> out = new ArrayList<>();
        for (SemanticCatalogEntry entry : catalog.metrics()) {
            double raw = scoreEntry(normText, textTokens, entry);
            out.add(new ScoredCandidate(
                    entry.columnName(), entry.label(), raw, raw >= 0.35, entry.rankScore()));
        }
        out.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed());
        return out;
    }

    private static double scoreEntry(String normText, Set<String> textTokens, SemanticCatalogEntry entry) {
        double score = 0;
        for (String phrase : phraseVariants(entry.columnName(), entry.label())) {
            String normPhrase = normalize(phrase);
            if (normPhrase.isBlank()) continue;
            if (normText.contains(normPhrase)) {
                score = Math.max(score, 0.85 + (normPhrase.length() / (double) Math.max(normText.length(), 1)) * 0.1);
            }
            Set<String> phraseTokens = tokens(normPhrase);
            if (!phraseTokens.isEmpty()) {
                long overlap = phraseTokens.stream().filter(textTokens::contains).count();
                double tokenScore = (double) overlap / phraseTokens.size();
                if (tokenScore >= 0.5) {
                    score = Math.max(score, 0.45 + tokenScore * 0.45);
                }
            }
        }
        return score * entry.rankScore();
    }

    private static List<String> phraseVariants(String columnName, String label) {
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

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Set<String> tokens(String normalized) {
        Set<String> out = new LinkedHashSet<>();
        if (normalized == null || normalized.isBlank()) return out;
        for (String t : normalized.split("\\s+")) {
            if (t.length() >= 2) out.add(t);
            String sing = singularizeToken(t);
            if (!sing.equals(t)) out.add(sing);
        }
        return out;
    }

    private static String singularize(String phrase) {
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

    private static void printCandidates(List<ScoredCandidate> candidates) {
        for (ScoredCandidate c : candidates) {
            System.out.printf("  %-22s label=%-18s score=%.4f passes_0.35=%s rankWeight=%.2f%n",
                    c.column(), c.label(), c.score(), c.passesThreshold(), c.rankWeight());
        }
    }

    private static void explainWinner(
            String question, String metricHint, SemanticCatalog catalog,
            CatalogQuestionMatcher.MatchResult winner, List<ScoredCandidate> fullQ
    ) {
        if (!winner.resolved()) {
            System.out.println("  REASON: no candidate scored >= 0.35 threshold");
            return;
        }
        ScoredCandidate top = fullQ.isEmpty() ? null : fullQ.getFirst();
        if (metricHint != null) {
            System.out.println("  REASON: bestMetric(question, \"" + metricHint + "\") — hint prepended to text");
        } else {
            System.out.println("  REASON: bestMetric(full question) — highest score wins");
        }
        if (top != null && !top.column().equals(winner.columnName())) {
            System.out.println("  NOTE: full-question top=" + top.column()
                    + " but winner=" + winner.columnName() + " (hint path may differ)");
        }
    }

    private static void explainProfitMapping(
            String question, String metricHint, SemanticCatalog catalog,
            QuestionSemantics semantics, MetricResolution resolution
    ) {
        for (String term : List.of("profit", "profitability", "profitable", "profit margin")) {
            if (!question.toLowerCase(Locale.ROOT).contains(term.replace(" ", ""))
                    && !question.toLowerCase(Locale.ROOT).contains(term)) {
                continue;
            }
            System.out.println("  term \"" + term + "\":");
            ResolvedEntity dict = ENTITY_RESOLVER.matchFragment(term);
            System.out.println("    SemanticDictionary.matchFragment → "
                    + (dict != null ? dict.columnKey() : "null (no oil entries in taxi dictionary)"));
            List<ScoredCandidate> hintScores = scoreAllMetrics(question, term, catalog);
            ScoredCandidate best = hintScores.isEmpty() ? null : hintScores.getFirst();
            System.out.println("    catalog score with hint \"" + term + "\": "
                    + (best != null ? best.column() + "=" + String.format("%.4f", best.score()) : "n/a"));
            boolean profitMarginToken = tokens(normalize("profit margin")).contains("profit")
                    && tokens(normalize("profit margin")).contains("margin");
            System.out.println("    profit_margin phraseVariants: [profit_margin, profit margin, profit margin]");
            System.out.println("    token overlap with \"" + term + "\": "
                    + describeTokenOverlap(term, "profit_margin"));
            if ("profitability".equals(term) || "profitable".equals(term)) {
                System.out.println("    GAP: no stem/synonym map profitability→profit; "
                        + "token \"profitability\" does not match \"profit\" (length/substring rules) "
                        + "nor \"margin\"");
            }
        }
    }

    private static String describeTokenOverlap(String questionTerm, String column) {
        Set<String> qTok = tokens(normalize(questionTerm));
        Set<String> colTok = tokens(normalize(column.replace('_', ' ')));
        long overlap = colTok.stream().filter(qTok::contains).count();
        return overlap + "/" + colTok.size() + " column tokens matched";
    }

    private static String blankToNone(String s) {
        return s == null || s.isBlank() ? "none" : s;
    }

    private record ScoredCandidate(
            String column, String label, double score, boolean passesThreshold, double rankWeight
    ) {}

    private static RegistryResolutionBundle oilBundle() {
        String table = "oil_operations";
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor("oil", table, List.of("well_id"), List.of("oil_gas"))),
                List.of(
                        new MetricDescriptor(table + ".profit_margin", "profit_margin", "FLOAT", "AVG", null),
                        new MetricDescriptor(table + ".total_revenue", "total_revenue", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".downtime_hours", "downtime_hours", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".maintenance_cost", "maintenance_cost", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".carbon_emission", "carbon_emission", "FLOAT", "SUM", null)
                ),
                List.of(
                        new DimensionDescriptor(table + ".oil_field", table + ".oil_field", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".region", table + ".region", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".facility_type", table + ".facility_type", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".product_type", table + ".product_type", "CATEGORICAL")
                ),
                null);
    }
}
