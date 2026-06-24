package com.example.BACKEND.catalogue.decision.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.semantic.EntityKind;
import com.example.BACKEND.catalogue.decision.semantic.QueryEntityResolver;
import com.example.BACKEND.catalogue.decision.semantic.ResolvedEntity;
import com.example.BACKEND.catalogue.decision.semantics.AnalyticalRelationship;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SchemaDrivenQuestionResolver;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SchemaDrivenQuestionResolver.SchemaDrivenResolution;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticCatalog;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticCatalogBuilder;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticDiscoveryDebug;
import com.example.BACKEND.catalogue.decision.semantics.RelationshipIntentDetector;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 1 — schema-driven semantic discovery, then entity extraction and dimension resolution.
 */
@Component
public class QuestionInvestigationPlanner {

    private static final Pattern CONTRIBUTE = Pattern.compile(
            "(?i)how\\s+(?:does|do)\\s+(.+?)\\s+contribute(?:s)?\\s+to\\s+(.+?)\\??$");
    private static final Pattern AFFECT = Pattern.compile(
            "(?i)how\\s+(?:does|do)\\s+(.+?)\\s+(?:affect|impact|influence|drive)\\s+(.+?)\\??$");
    private static final Pattern RANK = Pattern.compile("(?i)(?:top|highest|lowest|rank)\\s+(.+?)\\s+by\\s+(.+)");
    private static final Pattern BY_DIMENSION = Pattern.compile("(?i)(.+?)\\s+by\\s+(.+)");

    private final QuestionSemanticExtractor semanticExtractor;
    private final QueryEntityResolver entityResolver;
    private final DimensionResolver dimensionResolver;
    private final InvestigationStepPlanner stepPlanner;
    private final SemanticCatalogBuilder catalogBuilder;
    private final SchemaDrivenQuestionResolver schemaResolver;
    private final RelationshipIntentDetector relationshipDetector;

    public QuestionInvestigationPlanner(
            QuestionSemanticExtractor semanticExtractor,
            QueryEntityResolver entityResolver,
            DimensionResolver dimensionResolver,
            InvestigationStepPlanner stepPlanner,
            SemanticCatalogBuilder catalogBuilder,
            SchemaDrivenQuestionResolver schemaResolver,
            RelationshipIntentDetector relationshipDetector
    ) {
        this.semanticExtractor = semanticExtractor;
        this.entityResolver = entityResolver;
        this.dimensionResolver = dimensionResolver;
        this.stepPlanner = stepPlanner;
        this.catalogBuilder = catalogBuilder;
        this.schemaResolver = schemaResolver;
        this.relationshipDetector = relationshipDetector;
    }

    public QuestionInvestigation plan(String question, RegistryResolutionBundle bundle) {
        SemanticCatalog catalog = catalogBuilder.build(bundle);
        SchemaDrivenResolution schema = schemaResolver.resolve(question, catalog);
        QuestionSemantics semantics = overlaySchema(
                semanticExtractor.extract(question, bundle), schema);
        ExtractedQuestionEntities extraction = extractEntities(question, semantics, schema);
        ResolvedDimension dimension = dimensionResolver.resolve(extraction, bundle, question, catalog);
        var steps = stepPlanner.plan(extraction, dimension);
        boolean executable = extraction.isShareAnalysis()
                || extraction.isRelationshipAnalysis()
                || (dimension.resolved() && extraction.metricKey() != null);
        SemanticDiscoveryDebug discovery = schema.discovery() != null
                ? schema.discovery()
                : SemanticDiscoveryDebug.empty(catalog);
        return new QuestionInvestigation(extraction, dimension, steps, executable, catalog, discovery);
    }

    private QuestionSemantics overlaySchema(QuestionSemantics semantics, SchemaDrivenResolution schema) {
        if (semantics == null || !schema.usable()) return semantics;
        if (semantics.relationship() == AnalyticalRelationship.SHARE_OF_TOTAL) {
            return semantics;
        }

        String metric = schema.metricColumn() != null ? schema.metricColumn() : semantics.primaryMetric();
        String metricLabel = metric != null ? SemanticCatalogBuilder.humanize(metric) : semantics.primaryMetricLabel();
        String dimension = schema.dimensionColumn() != null ? schema.dimensionColumn() : semantics.dimension();
        String dimensionLabel = dimension != null
                ? SemanticCatalogBuilder.humanize(dimension) : semantics.dimensionLabel();
        AnalyticalIntentType intent = shouldPreferSchemaIntent(schema)
                ? mapIntentType(schema.intent(), semantics.intent())
                : semantics.intent();
        double confidence = Math.max(semantics.confidence(), schema.partiallyResolved() ? 0.78 : 0.5);

        return new QuestionSemantics(
                semantics.question(),
                metric, metricLabel,
                semantics.targetMetric(), semantics.targetMetricLabel(),
                dimension, dimensionLabel,
                dimension != null ? dimension : semantics.grouping(),
                intent, mapRelationship(intent, semantics.relationship()),
                semantics.temporalReferences(),
                confidence,
                semantics.extractedEntities());
    }

    private ExtractedQuestionEntities extractEntities(
            String question, QuestionSemantics semantics, SchemaDrivenResolution schema
    ) {
        String entityPhrase = subjectPhrase(question);
        String entityKey = resolveEntityKey(entityPhrase, semantics);

        AnalyticalInvestigationIntent intent = shouldPreferSchemaIntent(schema)
                ? schema.intent()
                : mapIntent(semantics);
        if (relationshipDetector.matches(question)
                || semantics.intent() == AnalyticalIntentType.RELATIONSHIP) {
            intent = AnalyticalInvestigationIntent.RELATIONSHIP;
        }
        String metricKey = semantics.primaryMetric();
        String metricLabel = semantics.primaryMetricLabel() != null
                ? semantics.primaryMetricLabel() : metricKey;
        String target = semantics.targetMetric();

        if (entityKey == null && schema.dimensionColumn() != null) {
            entityKey = schema.dimensionColumn();
            if (entityPhrase == null) {
                entityPhrase = SemanticCatalogBuilder.humanize(schema.dimensionColumn());
            }
        } else if (entityKey == null && semantics.dimension() != null) {
            entityKey = semantics.dimension();
            if (entityPhrase == null) {
                entityPhrase = semantics.dimensionLabel() != null
                        ? semantics.dimensionLabel() : semantics.dimension();
            }
        }

        return new ExtractedQuestionEntities(
                question, metricKey, metricLabel, target,
                entityPhrase, entityKey, intent, semantics.confidence());
    }

    private String subjectPhrase(String question) {
        if (question == null) return null;
        if (relationshipDetector.matches(question)) return null;
        String trimmed = question.trim();
        Matcher contrib = CONTRIBUTE.matcher(trimmed);
        if (contrib.find()) return contrib.group(1).trim();
        Matcher affect = AFFECT.matcher(trimmed);
        if (affect.find()) return affect.group(1).trim();
        Matcher rank = RANK.matcher(trimmed);
        if (rank.find()) return rank.group(1).trim();
        Matcher by = BY_DIMENSION.matcher(trimmed);
        if (by.find()) return by.group(2).trim();
        return null;
    }

    private String resolveEntityKey(String phrase, QuestionSemantics semantics) {
        if (phrase != null && !phrase.isBlank()) {
            ResolvedEntity matched = entityResolver.matchFragment(phrase);
            if (matched != null && !isMetricKind(matched)) {
                return matched.columnKey();
            }
        }
        if (semantics.dimension() != null) return semantics.dimension();
        return null;
    }

    private boolean isMetricKind(ResolvedEntity e) {
        return e.kind() == EntityKind.METRIC || e.kind() == EntityKind.DERIVED_METRIC;
    }

    private AnalyticalInvestigationIntent mapIntent(QuestionSemantics semantics) {
        if (semantics.relationship() == AnalyticalRelationship.SHARE_OF_TOTAL) {
            return AnalyticalInvestigationIntent.SHARE_OF_TOTAL;
        }
        return switch (semantics.intent()) {
            case CONTRIBUTION, COMPOSITION -> AnalyticalInvestigationIntent.CONTRIBUTION;
            case COMPARISON -> AnalyticalInvestigationIntent.COMPARISON;
            case RANKING -> AnalyticalInvestigationIntent.RANKING;
            case TREND_ANALYSIS, FORECASTING -> AnalyticalInvestigationIntent.TREND;
            case DISTRIBUTION, SEGMENTATION -> AnalyticalInvestigationIntent.DISTRIBUTION;
            case EFFICIENCY -> AnalyticalInvestigationIntent.EFFICIENCY;
            case RELATIONSHIP -> AnalyticalInvestigationIntent.RELATIONSHIP;
            default -> switch (semantics.relationship()) {
                case METRIC_RELATIONSHIP -> AnalyticalInvestigationIntent.RELATIONSHIP;
                case RANKING -> AnalyticalInvestigationIntent.RANKING;
                case TREND_OVER_TIME -> AnalyticalInvestigationIntent.TREND;
                case DISTRIBUTION -> AnalyticalInvestigationIntent.DISTRIBUTION;
                case COMPARISON -> AnalyticalInvestigationIntent.COMPARISON;
                default -> AnalyticalInvestigationIntent.CONTRIBUTION;
            };
        };
    }

    private boolean shouldPreferSchemaIntent(SchemaDrivenResolution schema) {
        if (schema == null || !schema.usable()) return false;
        return schema.intent() == AnalyticalInvestigationIntent.RANKING
                || schema.intent() == AnalyticalInvestigationIntent.EFFICIENCY;
    }

    private AnalyticalIntentType mapIntentType(
            AnalyticalInvestigationIntent schemaIntent, AnalyticalIntentType fallback
    ) {
        if (schemaIntent == null) return fallback;
        return switch (schemaIntent) {
            case CONTRIBUTION, SHARE_OF_TOTAL -> AnalyticalIntentType.CONTRIBUTION;
            case COMPARISON -> AnalyticalIntentType.COMPARISON;
            case RANKING -> AnalyticalIntentType.RANKING;
            case TREND -> AnalyticalIntentType.TREND_ANALYSIS;
            case DISTRIBUTION -> AnalyticalIntentType.DISTRIBUTION;
            case EFFICIENCY -> AnalyticalIntentType.EFFICIENCY;
            case RELATIONSHIP -> AnalyticalIntentType.RELATIONSHIP;
            case EXACT_LOOKUP -> AnalyticalIntentType.GENERAL_ANALYSIS;
        };
    }

    private AnalyticalRelationship mapRelationship(
            AnalyticalIntentType intent, AnalyticalRelationship fallback
    ) {
        return switch (intent) {
            case RANKING -> AnalyticalRelationship.RANKING;
            case TREND_ANALYSIS, FORECASTING -> AnalyticalRelationship.TREND_OVER_TIME;
            case COMPARISON -> AnalyticalRelationship.COMPARISON;
            case DISTRIBUTION, SEGMENTATION -> AnalyticalRelationship.DISTRIBUTION;
            case EFFICIENCY -> AnalyticalRelationship.EFFICIENCY;
            case RELATIONSHIP -> AnalyticalRelationship.METRIC_RELATIONSHIP;
            default -> fallback != null ? fallback : AnalyticalRelationship.DIMENSION_BREAKDOWN;
        };
    }
}
