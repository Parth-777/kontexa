package com.example.BACKEND.catalogue.decision.semantics;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.semantic.EntityKind;
import com.example.BACKEND.catalogue.decision.semantic.QueryEntityResolver;
import com.example.BACKEND.catalogue.decision.semantic.ResolvedEntity;
import com.example.BACKEND.catalogue.decision.semantic.SemanticDictionary;
import com.example.BACKEND.catalogue.decision.semantics.catalog.CatalogQuestionMatcher;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SchemaDrivenMetricResolver;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SchemaDrivenMetricResolver.SchemaDrivenMetricResult;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticCatalog;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticCatalogBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts analytical entities and intent from natural-language questions.
 * Single source of truth — no trip_distance defaults.
 */
@Component
public class   QuestionSemanticExtractor {

    private static final Pattern CONTRIBUTE_TO = Pattern.compile(
            "(?i)how\\s+(?:does|do)\\s+(.+?)\\s+contribute(?:s)?\\s+to\\s+(.+?)\\??$");
    private static final Pattern SHARE_CONTRIBUTION = Pattern.compile(
            "(?i)(.+?)\\s+contribution\\s+to\\s+(.+?)\\??$");
    private static final Pattern AFFECT = Pattern.compile(
            "(?i)(?:how\\s+(?:does|do)|does|do)\\s+(.+?)\\s+(?:affect|impact|influence|drive|hurt)\\s+(.+?)\\??$");

    private final QueryEntityResolver entityResolver;
    private final SemanticDictionary dictionary;
    private final SemanticCatalogBuilder catalogBuilder;
    private final CatalogQuestionMatcher catalogMatcher;
    private final SchemaDrivenMetricResolver schemaMetricResolver;
    private final RelationshipIntentDetector relationshipDetector;

    public QuestionSemanticExtractor(
            QueryEntityResolver entityResolver,
            SemanticDictionary dictionary,
            SemanticCatalogBuilder catalogBuilder,
            CatalogQuestionMatcher catalogMatcher,
            SchemaDrivenMetricResolver schemaMetricResolver,
            RelationshipIntentDetector relationshipDetector
    ) {
        this.entityResolver = entityResolver;
        this.dictionary = dictionary;
        this.catalogBuilder = catalogBuilder;
        this.catalogMatcher = catalogMatcher;
        this.schemaMetricResolver = schemaMetricResolver;
        this.relationshipDetector = relationshipDetector;
    }

    public QuestionSemantics extract(String question, RegistryResolutionBundle bundle) {
        if (question == null || question.isBlank()) {
            return QuestionSemantics.unresolved("");
        }

        SemanticCatalog catalog = catalogBuilder.build(bundle);
        List<ResolvedEntity> entities = matchFromCatalog(question, catalog);
        if (entities.isEmpty()) {
            entities = matchWithWordBoundaries(question);
        }
        List<String> entityKeys = entities.stream().map(ResolvedEntity::columnKey).distinct().toList();

        AnalyticalIntentType intent = detectIntent(question);
        AnalyticalRelationship relationship = detectRelationship(question, intent);

        ResolvedEntity primaryMetric = pickPrimaryMetric(question, entities, relationship, bundle);
        ResolvedEntity targetMetric = pickTargetMetric(question, entities, relationship, primaryMetric, bundle);
        ResolvedEntity dimension = pickDimension(question, entities, relationship, primaryMetric, bundle);

        String grouping = resolveGrouping(dimension, primaryMetric, relationship);

        double confidence = scoreConfidence(entities, primaryMetric, dimension, relationship, question);

        return new QuestionSemantics(
                question,
                primaryMetric != null ? primaryMetric.columnKey() : null,
                primaryMetric != null ? primaryMetric.label() : null,
                targetMetric != null ? targetMetric.columnKey() : null,
                targetMetric != null ? targetMetric.label() : null,
                dimension != null ? dimension.columnKey() : null,
                dimension != null ? dimension.label() : null,
                grouping,
                intent,
                relationship,
                extractTemporal(question, entities),
                confidence,
                entityKeys
        );
    }

    private List<ResolvedEntity> matchWithWordBoundaries(String question) {
        String q = " " + question.toLowerCase(Locale.ROOT).replaceAll("[?!.,]", " ") + " ";
        List<ResolvedEntity> matches = new ArrayList<>();

        for (SemanticDictionary.DictionaryEntry e : dictionary.entries()) {
            String phrase = e.phrase().toLowerCase(Locale.ROOT);
            if (containsPhrase(q, phrase)) {
                double score = phrase.length() / (double) Math.max(1, question.length());
                matches.add(new ResolvedEntity(
                        e.phrase(), e.columnKey(), e.label(), e.kind(),
                        Math.min(0.98, 0.65 + score)));
            }
        }
        return dedupe(matches);
    }

    private boolean containsPhrase(String paddedQuestion, String phrase) {
        if (phrase.contains(" ")) {
            return paddedQuestion.contains(" " + phrase + " ");
        }
        return Pattern.compile("\\b" + Pattern.quote(phrase) + "\\b")
                .matcher(paddedQuestion).find();
    }

    private List<ResolvedEntity> dedupe(List<ResolvedEntity> matches) {
        return matches.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ResolvedEntity::columnKey, e -> e,
                        (a, b) -> a.matchScore() >= b.matchScore() ? a : b))
                .values().stream()
                .sorted(Comparator.comparingDouble(ResolvedEntity::matchScore).reversed())
                .toList();
    }

    private AnalyticalIntentType detectIntent(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        if (relationshipDetector.matches(question)) {
            return AnalyticalIntentType.RELATIONSHIP;
        }
        if (mentionsContribution(q) || q.contains("share of") || q.contains("portion of")) {
            return AnalyticalIntentType.CONTRIBUTION;
        }
        if (q.contains("top ") || q.contains("rank") || q.contains("highest") || q.contains("lowest")
                || q.contains("longest") || q.contains("shortest") || q.contains("strongest")
                || q.contains("weakest") || q.contains(" the most") || q.contains("most?")) {
            return AnalyticalIntentType.RANKING;
        }
        if (q.contains("distribution") || q.contains("histogram") || q.contains("spread")) {
            return AnalyticalIntentType.DISTRIBUTION;
        }
        if (isTrendQuestion(q)) {
            return AnalyticalIntentType.TREND_ANALYSIS;
        }
        if (q.contains(" vs ") || q.contains("compare") || q.contains("comparison")
                || q.contains("side by side") || q.contains("weekend") && q.contains("weekday")) {
            return AnalyticalIntentType.COMPARISON;
        }
        if (q.contains("efficiency") || q.contains("per mile") || q.contains("per trip")) {
            return AnalyticalIntentType.EFFICIENCY;
        }
        return AnalyticalIntentType.GENERAL_ANALYSIS;
    }

    private static boolean isTrendQuestion(String q) {
        if (q.contains("trend") || q.contains("over time") || q.contains("by hour")
                || q.contains("hourly") || q.contains("monthly") || q.contains("by day")) {
            return true;
        }
        if (q.contains("moved across") || q.contains("movement across")
                || q.contains("changing across") || q.contains("pattern over")) {
            return true;
        }
        return q.matches(".*\\b(over|across)\\s+[\\w\\s]*\\b(week|month|hour|day)s?\\b.*");
    }

    private AnalyticalRelationship detectRelationship(String question, AnalyticalIntentType intent) {
        String q = question.toLowerCase(Locale.ROOT);
        if (mentionsContribution(q) && (q.contains("revenue") || q.contains("total"))) {
            if (SHARE_CONTRIBUTION.matcher(question.trim()).find()) {
                return AnalyticalRelationship.SHARE_OF_TOTAL;
            }
            return AnalyticalRelationship.DIMENSION_BREAKDOWN;
        }
        return switch (intent) {
            case RELATIONSHIP -> AnalyticalRelationship.METRIC_RELATIONSHIP;
            case CONTRIBUTION, COMPOSITION -> AnalyticalRelationship.DIMENSION_BREAKDOWN;
            case TREND_ANALYSIS, FORECASTING -> AnalyticalRelationship.TREND_OVER_TIME;
            case RANKING -> AnalyticalRelationship.RANKING;
            case COMPARISON -> AnalyticalRelationship.COMPARISON;
            case DISTRIBUTION, SEGMENTATION -> AnalyticalRelationship.DISTRIBUTION;
            case EFFICIENCY -> AnalyticalRelationship.EFFICIENCY;
            default -> AnalyticalRelationship.DIMENSION_BREAKDOWN;
        };
    }

    private ResolvedEntity pickPrimaryMetric(
            String question, List<ResolvedEntity> entities, AnalyticalRelationship rel,
            RegistryResolutionBundle bundle
    ) {
        Matcher share = SHARE_CONTRIBUTION.matcher(question.trim());
        if (share.find() && rel == AnalyticalRelationship.SHARE_OF_TOTAL) {
            ResolvedEntity num = resolveMetricFragment(share.group(1).trim(), question, bundle);
            if (num != null && isMetricKind(num)) return num;
        }
        Matcher contrib = CONTRIBUTE_TO.matcher(question.trim());
        if (contrib.find()) {
            if (rel == AnalyticalRelationship.SHARE_OF_TOTAL) {
                ResolvedEntity num = resolveMetricFragment(contrib.group(1), question, bundle);
                if (num != null && isMetricKind(num)) return num;
            }
            if (rel == AnalyticalRelationship.DIMENSION_BREAKDOWN) {
                ResolvedEntity revenue = resolveMetricFragment(contrib.group(2), question, bundle);
                if (revenue != null && isMetricKind(revenue)) return revenue;
            }
        }

        if (rel == AnalyticalRelationship.METRIC_RELATIONSHIP) {
            RelationshipRoles roles = resolveRelationshipRoles(question, bundle);
            if (roles != null && roles.primary() != null) return roles.primary();
        }

        SchemaDrivenMetricResult schema = schemaMetricResolver.resolve(question, bundle);
        if (schema.resolved()) {
            return new ResolvedEntity(
                    schema.primaryLabel(), schema.primaryColumn(), schema.primaryLabel(),
                    EntityKind.METRIC, schema.confidence());
        }

        return entities.stream()
                .filter(this::isMetricKind)
                .max(Comparator.comparingDouble(ResolvedEntity::matchScore))
                .orElse(null);
    }

    private ResolvedEntity resolveDimensionFragment(
            String fragment, String question, RegistryResolutionBundle bundle
    ) {
        if (fragment == null || fragment.isBlank()) return null;
        SemanticCatalog catalog = catalogBuilder.build(bundle);
        if (catalog.hasSchema()) {
            var match = catalogMatcher.bestDimension(question, fragment.trim(), catalog);
            if (match.resolved()) {
                return new ResolvedEntity(
                        match.label(), match.columnName(), match.label(),
                        EntityKind.DIMENSION, match.score());
            }
        }
        return null;
    }

    private ResolvedEntity resolveMetricFragment(
            String fragment, String question, RegistryResolutionBundle bundle
    ) {
        if (fragment == null || fragment.isBlank()) return null;
        SemanticCatalog catalog = catalogBuilder.build(bundle);
        if (catalog.hasSchema()) {
            var match = catalogMatcher.bestMetric(question, fragment.trim(), catalog);
            if (match.resolved()) {
                return new ResolvedEntity(
                        match.label(), match.columnName(), match.label(),
                        EntityKind.METRIC, match.score());
            }
        }
        ResolvedEntity dict = matchFragment(fragment);
        return dict != null && isMetricKind(dict) ? dict : null;
    }

    private List<ResolvedEntity> matchFromCatalog(String question, SemanticCatalog catalog) {
        if (!catalog.hasSchema()) return List.of();
        List<ResolvedEntity> enriched = new ArrayList<>();
        CatalogQuestionMatcher.MatchResult metric = catalogMatcher.bestMetric(question, catalog);
        if (metric.resolved()) {
            enriched.add(new ResolvedEntity(
                    metric.label(), metric.columnName(), metric.label(),
                    EntityKind.METRIC, metric.score()));
        }
        CatalogQuestionMatcher.MatchResult dimension = catalogMatcher.bestDimension(question, catalog);
        if (dimension.resolved()) {
            enriched.add(new ResolvedEntity(
                    dimension.label(), dimension.columnName(), dimension.label(),
                    EntityKind.DIMENSION, dimension.score()));
        }
        return dedupe(enriched);
    }

    private ResolvedEntity pickTargetMetric(
            String question, List<ResolvedEntity> entities,
            AnalyticalRelationship rel, ResolvedEntity primary,
            RegistryResolutionBundle bundle
    ) {
        Matcher share = SHARE_CONTRIBUTION.matcher(question.trim());
        if (share.find() && rel == AnalyticalRelationship.SHARE_OF_TOTAL) {
            ResolvedEntity denom = resolveMetricFragment(share.group(2).trim(), question, bundle);
            if (denom != null && isMetricKind(denom)) return denom;
        }
        Matcher contrib = CONTRIBUTE_TO.matcher(question.trim());
        if (contrib.find()) {
            ResolvedEntity denom = resolveMetricFragment(contrib.group(2), question, bundle);
            if (denom != null && isMetricKind(denom)) return denom;
        }

        if (rel == AnalyticalRelationship.METRIC_RELATIONSHIP) {
            RelationshipRoles roles = resolveRelationshipRoles(question, bundle);
            if (roles != null && roles.relationship() != null) return roles.relationship();
            return entities.stream()
                    .filter(this::isMetricKind)
                    .filter(e -> primary == null || !e.columnKey().equals(primary.columnKey()))
                    .findFirst()
                    .orElse(null);
        }

        SchemaDrivenMetricResult schema = schemaMetricResolver.resolve(question, bundle);
        if (schema.secondaryColumn() != null) {
            return new ResolvedEntity(
                    schema.secondaryLabel(), schema.secondaryColumn(), schema.secondaryLabel(),
                    EntityKind.METRIC, schema.confidence());
        }

        if (rel == AnalyticalRelationship.SHARE_OF_TOTAL) {
            return entities.stream()
                    .filter(e -> isMetricKind(e) && (primary == null || !e.columnKey().equals(primary.columnKey())))
                    .filter(e -> e.columnKey().contains("total") || e.label().toLowerCase(Locale.ROOT).contains("revenue"))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private ResolvedEntity pickDimension(
            String question, List<ResolvedEntity> entities,
            AnalyticalRelationship rel, ResolvedEntity primaryMetric,
            RegistryResolutionBundle bundle
    ) {
        if (rel == AnalyticalRelationship.METRIC_RELATIONSHIP) {
            return null;
        }
        Matcher affect = AFFECT.matcher(question.trim());
        if (affect.find()) {
            ResolvedEntity subject = matchFragment(affect.group(1));
            if (subject != null && isDimensionKind(subject)) return subject;
        }

        Matcher contrib = CONTRIBUTE_TO.matcher(question.trim());
        if (contrib.find() && rel == AnalyticalRelationship.DIMENSION_BREAKDOWN) {
            ResolvedEntity subject = resolveDimensionFragment(contrib.group(1), question, bundle);
            if (subject == null) {
                subject = matchFragment(contrib.group(1));
            }
            if (subject != null && isDimensionKind(subject)) return subject;
        }

        return entities.stream()
                .filter(this::isDimensionKind)
                .max(Comparator.comparingDouble(ResolvedEntity::matchScore))
                .orElse(null);
    }

    private record RelationshipRoles(ResolvedEntity primary, ResolvedEntity relationship) {}

    private RelationshipRoles resolveRelationshipRoles(String question, RegistryResolutionBundle bundle) {
        String[] slots = relationshipDetector.slotPhrases(question);
        if (slots == null) return null;

        ResolvedEntity source = slots.length > 0 && slots[0] != null && !slots[0].isBlank()
                ? resolveMetricFragment(slots[0], question, bundle) : null;
        ResolvedEntity target = slots.length > 1 && slots[1] != null && !slots[1].isBlank()
                ? resolveMetricFragment(slots[1], question, bundle) : null;
        if (source == null && target == null) return null;

        if (relationshipPrimaryIsOutcome(question)) {
            ResolvedEntity primary = target != null ? target : source;
            ResolvedEntity relationship = source != null && target != null && !sameColumn(source, target)
                    ? source : null;
            return new RelationshipRoles(primary, relationship);
        }

        ResolvedEntity primary = source != null ? source : target;
        ResolvedEntity relationship = source != null && target != null && !sameColumn(source, target)
                ? target : null;
        return new RelationshipRoles(primary, relationship);
    }

    private static boolean relationshipPrimaryIsOutcome(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        if (q.contains(" associated with ")) return false;
        if (q.contains(" versus ")) return false;
        if (q.contains("link between")) return false;
        if (q.contains(" linked to ")) return false;
        if (q.contains(" tied to ")) return false;
        if (q.contains(" climbing with ")) return false;
        return true;
    }

    private static boolean sameColumn(ResolvedEntity a, ResolvedEntity b) {
        return a != null && b != null && a.columnKey().equalsIgnoreCase(b.columnKey());
    }

    private String resolveGrouping(ResolvedEntity dimension, ResolvedEntity metric, AnalyticalRelationship rel) {
        if (rel == AnalyticalRelationship.METRIC_RELATIONSHIP) {
            return "relationship";
        }
        if (dimension == null) {
            if (rel == AnalyticalRelationship.SHARE_OF_TOTAL && metric != null) {
                return "composition";
            }
            return null;
        }
        String col = dimension.columnKey();
        if (col.endsWith("_flag") || col.endsWith("_bucket")) return col;
        if ("trip_distance".equals(col) || "pickup_zone".equals(col)
                || "fare_amount".equals(col) || "tip_amount".equals(col)) {
            return col + "_bucket";
        }
        if ("pickup_hour".equals(col) || "weekday".equals(col)) return col;
        return col + "_bucket";
    }

    private List<String> extractTemporal(String question, List<ResolvedEntity> entities) {
        List<String> refs = new ArrayList<>();
        String q = question.toLowerCase(Locale.ROOT);
        if (q.contains("hour")) refs.add("hour");
        if (q.contains("day") || q.contains("weekday") || q.contains("weekend")) refs.add("day");
        if (q.contains("month")) refs.add("month");
        entities.stream()
                .filter(e -> e.kind() == EntityKind.TEMPORAL_DIMENSION)
                .map(ResolvedEntity::columnKey)
                .forEach(refs::add);
        return refs.stream().distinct().toList();
    }

    private double scoreConfidence(
            List<ResolvedEntity> entities, ResolvedEntity metric,
            ResolvedEntity dimension, AnalyticalRelationship rel, String question
    ) {
        if (entities.isEmpty()) return 0.25;
        double score = 0.4;
        if (metric != null) score += 0.25;
        if (dimension != null) score += 0.2;
        if (rel == AnalyticalRelationship.SHARE_OF_TOTAL && metric != null) score += 0.1;
        if (question.toLowerCase(Locale.ROOT).contains("distance") && dimension == null) score -= 0.15;
        return Math.min(0.98, Math.max(0.2, score));
    }

    private ResolvedEntity matchFragment(String fragment) {
        if (fragment == null || fragment.isBlank()) return null;
        return entityResolver.matchFragment(fragment.trim());
    }

    private boolean isMetricKind(ResolvedEntity e) {
        return e.kind() == EntityKind.METRIC || e.kind() == EntityKind.DERIVED_METRIC;
    }

    private boolean isDimensionKind(ResolvedEntity e) {
        return e.kind() == EntityKind.DIMENSION || e.kind() == EntityKind.TEMPORAL_DIMENSION;
    }

    private static boolean mentionsContribution(String q) {
        return q.contains("contribution") || q.contains("contribute");
    }
}
