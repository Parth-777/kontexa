package com.example.BACKEND.catalogue.decision.semantic;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.semantic.AnalyticalIntentPatterns.IntentMatch;
import com.example.BACKEND.catalogue.decision.semantic.AnalyticalIntentPatterns.PatternKind;
import com.example.BACKEND.catalogue.decision.semantic.ContributionAnalysisPlan;
import com.example.BACKEND.catalogue.decision.semantic.DimensionImpactPlan;
import com.example.BACKEND.catalogue.decision.semantic.ResolvedEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Dedicated semantic parser — runs before analytical planning.
 */
@Service
public class SemanticAnalyticalParser {

    private static final Logger log = LoggerFactory.getLogger(SemanticAnalyticalParser.class);

    private final QueryEntityResolver entityResolver;
    private final ContributionQuestionParser contributionParser;
    private final DimensionImpactParser dimensionParser;

    public SemanticAnalyticalParser(
            QueryEntityResolver entityResolver,
            ContributionQuestionParser contributionParser,
            DimensionImpactParser dimensionParser
    ) {
        this.entityResolver = entityResolver;
        this.contributionParser = contributionParser;
        this.dimensionParser = dimensionParser;
    }

    /**
     * Exploratory parse — never hard-fails; applies fallback heuristics with reduced confidence.
     */
    public SemanticAnalysisPlan parseExploratory(String question, RegistryResolutionBundle bundle) {
        SemanticAnalysisPlan plan = parse(question, bundle);
        if (plan.parsed()) return plan;

        String q = question != null ? question.toLowerCase(Locale.ROOT) : "";
        ResolvedEntity metric = entityResolver.firstMetric(question);
        ResolvedEntity dim = entityResolver.firstDimension(question);

        String primaryMetric = metric != null ? metric.columnKey() : "total_amount";
        String primaryLabel = metric != null ? metric.label() : "Total Revenue";
        if (dim == null && !q.contains("distance") && !q.contains("mile")) {
            return SemanticAnalysisPlan.failure("no resolvable dimension — clarification required");
        }

        String grouping = dim != null ? bucketColumn(dim.columnKey()) : null;
        String groupingLabel = dim != null ? dim.label() : null;

        if (q.contains("tip")) {
            return new SemanticAnalysisPlan(
                    true, 0.55, PatternKind.CONTRIBUTION, AnalyticalIntentType.COMPOSITION,
                    "tip_amount", "Tips", "total_amount", null, null,
                    ContributionAnalysisPlan.of("tip_amount", "Tips", "total_amount", "Total Revenue"),
                    null, List.of(),
                    "Exploratory fallback: tip share of revenue", "");
        }

        if (grouping == null) {
            return SemanticAnalysisPlan.failure("no grouping dimension resolved");
        }

        return new SemanticAnalysisPlan(
                true, 0.5, PatternKind.DIMENSION_IMPACT, AnalyticalIntentType.CONTRIBUTION,
                primaryMetric, primaryLabel, dim.columnKey(),
                grouping, groupingLabel,
                null,
                DimensionImpactPlan.of(dim.columnKey(), groupingLabel, primaryMetric, primaryLabel),
                entityResolver.resolveAll(question),
                "Exploratory fallback: SUM(" + primaryMetric + ") by " + grouping, "");
    }

    public SemanticAnalysisPlan parse(String question, RegistryResolutionBundle bundle) {
        if (question == null || question.isBlank()) {
            return SemanticAnalysisPlan.failure("empty question");
        }

        IntentMatch intentMatch = AnalyticalIntentPatterns.detect(question);
        List<ResolvedEntity> entities = entityResolver.resolveAll(question);

        ContributionAnalysisPlan contribution = null;
        DimensionImpactPlan dimensionImpact = null;
        String primaryMetric = null;
        String primaryLabel = null;
        String secondaryMetric = null;
        String grouping = null;
        String groupingLabel = null;
        double confidence = intentMatch.confidence();

        if (contributionParser.matches(question)) {
            contribution = contributionParser.parse(question);
            if (contribution != null) {
                primaryMetric = contribution.numeratorMetric();
                primaryLabel = contribution.numeratorLabel();
                secondaryMetric = contribution.denominatorMetric();
                grouping = null;
                groupingLabel = null;
                confidence = Math.max(confidence, 0.9);
                intentMatch = new IntentMatch(PatternKind.CONTRIBUTION, AnalyticalIntentType.COMPOSITION, confidence);
            }
        }

        if (contribution == null && dimensionParser.matches(question)) {
            dimensionImpact = dimensionParser.parse(question);
            if (dimensionImpact != null) {
                primaryMetric = dimensionImpact.metricColumn();
                primaryLabel = dimensionImpact.metricLabel();
                grouping = dimensionImpact.bucketStrategy();
                groupingLabel = dimensionImpact.dimensionLabel();
                secondaryMetric = dimensionImpact.dimensionColumn();
                confidence = Math.max(confidence, 0.88);
                intentMatch = new IntentMatch(PatternKind.DIMENSION_IMPACT, AnalyticalIntentType.CONTRIBUTION, confidence);
            }
        }

        if (primaryMetric == null) {
            ResolvedEntity metric = entityResolver.firstMetric(question);
            ResolvedEntity dim = entityResolver.firstDimension(question);
            if (metric != null) {
                primaryMetric = metric.columnKey();
                primaryLabel = metric.label();
            }
            if (dim != null) {
                grouping = bucketColumn(dim.columnKey());
                groupingLabel = dim.label();
                secondaryMetric = dim.columnKey();
            }
        }

        if (primaryMetric == null) {
            log.warn("[semantic-parser] could not resolve metric for: {}", question);
            return SemanticAnalysisPlan.failure("unresolved metric");
        }

        if (grouping != null && "segment".equalsIgnoreCase(grouping)) {
            grouping = null;
            groupingLabel = null;
        }

        if (grouping == null && contribution == null && dimensionImpact == null) {
            log.warn("[semantic-parser] no dimension resolved for: {}", question);
            return SemanticAnalysisPlan.failure("unresolved analytical relationship");
        }

        if (!metricAvailable(primaryMetric, bundle)) {
            return SemanticAnalysisPlan.failure("metric not in registry: " + primaryMetric);
        }

        String summary = buildSummary(intentMatch, primaryMetric, primaryLabel, grouping, contribution, dimensionImpact);

        log.info("[semantic-parser] resolved metric={} dimension={} intent={} plan={}",
                primaryMetric,
                grouping != null ? grouping : (secondaryMetric != null ? secondaryMetric : "none"),
                intentMatch.intent(),
                summary);

        return new SemanticAnalysisPlan(
                true, confidence, intentMatch.kind(), intentMatch.intent(),
                primaryMetric, primaryLabel, secondaryMetric,
                grouping, groupingLabel,
                contribution, dimensionImpact,
                entities, summary, ""
        );
    }

    private String buildSummary(
            IntentMatch intent, String metric, String label, String grouping,
            ContributionAnalysisPlan contribution, DimensionImpactPlan dimensionImpact
    ) {
        if (contribution != null) {
            return String.format(Locale.ROOT,
                    "Composition: %s share of %s via %s",
                    contribution.numeratorLabel(), contribution.denominatorLabel(),
                    contribution.shareFormula());
        }
        if (dimensionImpact != null) {
            return String.format(Locale.ROOT,
                    "Dimension impact: SUM(%s) by %s buckets (%s)",
                    dimensionImpact.metricColumn(), dimensionImpact.bucketStrategy(),
                    dimensionImpact.dimensionLabel());
        }
        return String.format(Locale.ROOT,
                "%s: %s grouped by %s",
                intent.intent().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                label != null ? label : metric,
                grouping != null ? grouping : "none");
    }

    private String bucketColumn(String dimension) {
        if (dimension == null) return null;
        if (dimension.endsWith("_bucket")) return dimension;
        if ("trip_distance".equals(dimension)) return "trip_distance_bucket";
        if ("pickup_hour".equals(dimension)) return "pickup_hour";
        return dimension + "_bucket";
    }

    private boolean metricAvailable(String key, RegistryResolutionBundle bundle) {
        if (bundle == null || bundle.metrics() == null) return true;
        String norm = key.toLowerCase(Locale.ROOT);
        return bundle.metrics().stream().anyMatch(m ->
                m.key() != null && m.key().toLowerCase(Locale.ROOT).contains(norm.replace("_", ""))
                        || norm.contains(m.key().toLowerCase(Locale.ROOT).replace("_", "")));
    }
}
