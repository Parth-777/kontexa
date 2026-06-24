package com.example.BACKEND.catalogue.decision.semantics.catalog;

import com.example.BACKEND.catalogue.decision.investigation.AnalyticalInvestigationIntent;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves question intent, metric, and dimension against a schema-driven {@link SemanticCatalog}.
 */
@Component
public class SchemaDrivenQuestionResolver {

    private static final Pattern WHICH_HIGHEST = Pattern.compile(
            "(?i)which\\s+(.+?)\\s+(?:generates|generate|produces|produce|has|have|yields|yield)\\s+"
                    + "(?:the\\s+)?(?:highest|lowest|most|least|best|worst|maximum|minimum|max|min)\\s+(.+?)\\??$");

    private static final Pattern WHICH_MOST = Pattern.compile(
            "(?i)which\\s+(.+?)\\s+(?:generates|generate|produces|produce)\\s+(?:the\\s+)?most\\s+(.+?)\\??$");

    private static final Pattern WHICH_ARE = Pattern.compile(
            "(?i)which\\s+(.+?)\\s+are\\s+(?:the\\s+)?(?:most|least\\s+)?(\\w+)\\??$");

    private static final Pattern WHICH_UNDERPERFORMING = Pattern.compile(
            "(?i)which\\s+(.+?)\\s+are\\s+underperforming\\??$");

    private static final Pattern WHICH_IS_MOST = Pattern.compile(
            "(?i)which\\s+(.+?)\\s+is\\s+(?:the\\s+)?(?:most|least)\\s+(\\w+)\\??$");

    private static final Pattern BY_DIMENSION = Pattern.compile(
            "(?i)\\bby\\s+([a-z][a-z0-9_\\s]+)");

    private final CatalogQuestionMatcher matcher;

    public SchemaDrivenQuestionResolver(CatalogQuestionMatcher matcher) {
        this.matcher = matcher;
    }

    public SchemaDrivenResolution resolve(String question, SemanticCatalog catalog) {
        if (question == null || question.isBlank() || catalog == null || !catalog.hasSchema()) {
            return SchemaDrivenResolution.unresolved(catalog);
        }

        String q = question.trim();
        AnalyticalInvestigationIntent intent = detectIntent(q);
        String dimensionHint = null;
        String metricHint = null;

        Matcher highest = WHICH_HIGHEST.matcher(q);
        if (highest.find()) {
            dimensionHint = highest.group(1).trim();
            metricHint = highest.group(2).trim();
            intent = AnalyticalInvestigationIntent.RANKING;
        } else {
            Matcher most = WHICH_MOST.matcher(q);
            if (most.find()) {
                dimensionHint = most.group(1).trim();
                metricHint = most.group(2).trim();
                intent = AnalyticalInvestigationIntent.RANKING;
            } else {
                Matcher under = WHICH_UNDERPERFORMING.matcher(q);
                if (under.find()) {
                    dimensionHint = under.group(1).trim();
                    intent = AnalyticalInvestigationIntent.RANKING;
                } else {
                    Matcher whichIsMost = WHICH_IS_MOST.matcher(q);
                    if (whichIsMost.find()) {
                        dimensionHint = whichIsMost.group(1).trim();
                        metricHint = whichIsMost.group(2).trim();
                        intent = AnalyticalInvestigationIntent.RANKING;
                    } else {
                        Matcher whichAre = WHICH_ARE.matcher(q);
                        if (whichAre.find()) {
                            dimensionHint = whichAre.group(1).trim();
                            String adj = whichAre.group(2).trim().toLowerCase(Locale.ROOT);
                            if (adj.contains("efficient") || adj.contains("productiv")) {
                                intent = AnalyticalInvestigationIntent.EFFICIENCY;
                            } else {
                                intent = AnalyticalInvestigationIntent.RANKING;
                            }
                        }
                    }
                }
            }
        }

        Matcher byDim = BY_DIMENSION.matcher(q);
        if (dimensionHint == null && byDim.find()) {
            dimensionHint = byDim.group(1).trim();
        }

        CatalogQuestionMatcher.MatchResult metricMatch =
                metricHint != null
                        ? matcher.bestMetric(q, metricHint, catalog)
                        : matcher.bestMetric(q, catalog);

        CatalogQuestionMatcher.MatchResult dimensionMatch =
                dimensionHint != null
                        ? matcher.bestDimension(q, dimensionHint, catalog)
                        : matcher.bestDimension(q, catalog);

        if (!metricMatch.resolved() && dimensionMatch.resolved()
                && intent == AnalyticalInvestigationIntent.EFFICIENCY) {
            metricMatch = pickEfficiencyMetric(catalog, q);
        }

        if (!metricMatch.resolved() && intent == AnalyticalInvestigationIntent.RANKING) {
            metricMatch = matcher.bestMetric(q, catalog);
        }

        SemanticDiscoveryDebug debug = new SemanticDiscoveryDebug(
                catalog.candidateMetricKeys(),
                catalog.candidateDimensionKeys(),
                metricMatch.resolved() ? metricMatch.columnName() : "UNRESOLVED",
                dimensionMatch.resolved() ? dimensionMatch.columnName() : "UNRESOLVED",
                intent.name(),
                metricMatch.score(),
                dimensionMatch.score());

        return new SchemaDrivenResolution(
                intent,
                metricMatch.resolved() ? metricMatch.columnName() : null,
                dimensionMatch.resolved() ? dimensionMatch.columnName() : null,
                metricMatch.resolved() ? metricMatch.registryKey() : null,
                dimensionMatch.resolved() ? dimensionMatch.registryKey() : null,
                debug,
                metricMatch.resolved() || dimensionMatch.resolved());
    }

    private CatalogQuestionMatcher.MatchResult pickEfficiencyMetric(SemanticCatalog catalog, String question) {
        CatalogQuestionMatcher.MatchResult best = CatalogQuestionMatcher.MatchResult.unresolved("metric");
        for (SemanticCatalogEntry m : catalog.metrics()) {
            String col = m.columnName().toLowerCase(Locale.ROOT);
            double score = 0;
            if (col.contains("efficiency") || col.contains("productivity") || col.contains("utilization")) {
                score = 0.9;
            } else if (col.contains("margin") || col.contains("rate") || col.contains("ratio")) {
                score = 0.6;
            }
            if (score > best.score()) {
                best = new CatalogQuestionMatcher.MatchResult(
                        m.columnName(), m.registryKey(), score, "metric", m.label());
            }
        }
        if (!best.resolved()) {
            return matcher.bestMetric(question, catalog);
        }
        return best;
    }

    private AnalyticalInvestigationIntent detectIntent(String q) {
        String lower = q.toLowerCase(Locale.ROOT);
        if (lower.contains("efficient") || lower.contains("efficiency") || lower.contains("productivity")) {
            return AnalyticalInvestigationIntent.EFFICIENCY;
        }
        if (lower.contains("underperform") || lower.contains("highest") || lower.contains("lowest")
                || lower.contains("top ") || lower.contains("best ") || lower.contains("worst ")
                || lower.startsWith("which ")) {
            return AnalyticalInvestigationIntent.RANKING;
        }
        if (lower.contains("contribute") || lower.contains("contribution") || lower.contains("share")) {
            return AnalyticalInvestigationIntent.CONTRIBUTION;
        }
        if (lower.contains("trend") || lower.contains("over time") || lower.contains("monthly")
                || lower.contains("weekly") || lower.contains("daily")) {
            return AnalyticalInvestigationIntent.TREND;
        }
        if (lower.contains("compare") || lower.contains(" vs ") || lower.contains("versus")) {
            return AnalyticalInvestigationIntent.COMPARISON;
        }
        if (lower.contains("affect") || lower.contains("impact") || lower.contains("distribution")
                || lower.contains("breakdown")) {
            return AnalyticalInvestigationIntent.DISTRIBUTION;
        }
        return AnalyticalInvestigationIntent.DISTRIBUTION;
    }

    public record SchemaDrivenResolution(
            AnalyticalInvestigationIntent intent,
            String metricColumn,
            String dimensionColumn,
            String metricRegistryKey,
            String dimensionRegistryKey,
            SemanticDiscoveryDebug discovery,
            boolean partiallyResolved
    ) {
        public boolean usable() {
            return metricColumn != null || dimensionColumn != null;
        }

        static SchemaDrivenResolution unresolved(SemanticCatalog catalog) {
            return new SchemaDrivenResolution(
                    AnalyticalInvestigationIntent.DISTRIBUTION, null, null, null, null,
                    SemanticDiscoveryDebug.empty(catalog), false);
        }
    }
}
