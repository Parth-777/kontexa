package com.example.BACKEND.catalogue.decision.semantics;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticRegistry;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricMatchCandidate;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SchemaDrivenMetricResolver;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SchemaDrivenMetricResolver.SchemaDrivenMetricResult;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticCatalogBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves metrics and dimensions against the tenant schema using bare column names.
 * Metric binding is schema-driven via {@link SchemaDrivenMetricResolver} — no taxi fallbacks.
 */
@Component
public class MetricResolutionEngine {

    private static final Logger log = LoggerFactory.getLogger(MetricResolutionEngine.class);

    private static final double SUBSTITUTION_THRESHOLD = 0.85;
    private static final Set<String> DISTANCE_HINTS = Set.of(
            "distance", "mile", "miles", "trip distance", "trip_distance");

    private final MetricSemanticRegistry metricRegistry;
    private final SchemaDrivenMetricResolver schemaMetricResolver;

    public MetricResolutionEngine(
            MetricSemanticRegistry metricRegistry,
            SchemaDrivenMetricResolver schemaMetricResolver
    ) {
        this.metricRegistry = metricRegistry;
        this.schemaMetricResolver = schemaMetricResolver;
    }

    public MetricResolution resolve(QuestionSemantics semantics, RegistryResolutionBundle bundle) {
        if (semantics == null || semantics.question() == null) {
            return MetricResolution.rejected("No question semantics");
        }

        SchemaDrivenMetricResult schemaResult = schemaMetricResolver.resolve(
                semantics.question(), bundle);
        logMetricDebug(semantics.question(), schemaResult);

        Set<String> registryMetrics = metricKeys(bundle);
        Set<String> registryDimensions = dimensionKeys(bundle);
        String q = semantics.question().toLowerCase(Locale.ROOT);
        boolean distanceQuestion = DISTANCE_HINTS.stream().anyMatch(q::contains);

        String primary = choosePrimary(semantics, schemaResult, registryMetrics);
        String target = chooseTarget(semantics, schemaResult, registryMetrics);
        boolean relationshipQuestion = isRelationshipQuestion(semantics);
        if (relationshipQuestion && target != null && target.equalsIgnoreCase(primary)) {
            target = alternateRelationshipMetric(semantics, schemaResult, primary, registryMetrics);
        }
        String dimension = relationshipQuestion
                ? null
                : resolveColumn(semantics.dimension(), registryDimensions, false);

        if (primary == null) {
            return MetricResolution.rejected("Could not resolve primary metric from question");
        }

        if (!distanceQuestion && isDistanceColumn(dimension) && semantics.confidence() < SUBSTITUTION_THRESHOLD) {
            return MetricResolution.rejected(
                    "Dimension trip_distance substituted without distance in question");
        }

        if (semantics.primaryMetric() != null && !semantics.primaryMetric().equalsIgnoreCase(primary)
                && semantics.confidence() < SUBSTITUTION_THRESHOLD
                && schemaResult.confidence() < SUBSTITUTION_THRESHOLD) {
            return MetricResolution.rejected(
                    "Low-confidence metric substitution: " + semantics.primaryMetric() + " → " + primary);
        }

        if (semantics.dimension() != null && dimension != null
                && !semantics.dimension().equalsIgnoreCase(dimension)
                && semantics.confidence() < SUBSTITUTION_THRESHOLD
                && !relationshipQuestion) {
            return MetricResolution.rejected(
                    "Low-confidence dimension substitution: " + semantics.dimension() + " → " + dimension);
        }

        String grouping = semantics.grouping();
        if (relationshipQuestion) {
            grouping = "relationship";
        } else if (grouping == null || grouping.isBlank()) {
            grouping = dimension != null ? bucketize(dimension, bundle) : "composition";
        }

        String primaryLabel = metricRegistry.resolve(primary)
                .map(m -> m.displayLabel())
                .orElse(schemaResult.primaryLabel() != null ? schemaResult.primaryLabel()
                        : (semantics.primaryMetricLabel() != null ? semantics.primaryMetricLabel() : primary));

        String targetLabel = target != null
                ? metricRegistry.resolve(target).map(m -> m.displayLabel())
                        .orElse(schemaResult.secondaryLabel() != null ? schemaResult.secondaryLabel()
                                : semantics.targetMetricLabel())
                : null;

        String relationshipVariable = relationshipQuestion ? target : null;
        String relationshipLabel = relationshipVariable != null
                ? (targetLabel != null ? targetLabel : relationshipVariable.replace('_', ' '))
                : null;

        String dimLabel = semantics.dimensionLabel() != null ? semantics.dimensionLabel()
                : (dimension != null ? dimension.replace('_', ' ') : "");

        double baseConfidence = Math.max(semantics.confidence(), schemaResult.confidence());
        double confidence = Math.min(0.98, baseConfidence
                + (registryContains(registryMetrics, primary) ? 0.1 : 0)
                + (relationshipQuestion || dimension == null || registryContains(registryDimensions, dimension) ? 0.05 : 0));

        MetricResolution resolution = new MetricResolution(
                primary, primaryLabel, target, targetLabel,
                relationshipVariable, relationshipLabel,
                dimension, dimLabel, grouping, confidence, false, "",
                schemaResult.candidates(), schemaResult.debug());
        logResolutionBindings(semantics, resolution);
        return resolution;
    }

    private boolean isRelationshipQuestion(QuestionSemantics semantics) {
        return semantics.intent() == AnalyticalIntentType.RELATIONSHIP
                || semantics.relationship() == AnalyticalRelationship.METRIC_RELATIONSHIP;
    }

    private void logResolutionBindings(QuestionSemantics semantics, MetricResolution resolution) {
        log.info("[resolution-debug] resolved_metric={}", resolution.primaryMetric());
        log.info("[resolution-debug] resolved_dimension={}", resolution.dimension());
        log.info("[resolution-debug] resolved_relationship_variable={}", resolution.relationshipVariable());
        log.info("[resolution-debug] resolved_intent={}", semantics.intent());
    }

    private String choosePrimary(
            QuestionSemantics semantics, SchemaDrivenMetricResult schema, Set<String> registryMetrics
    ) {
        if (isRelationshipQuestion(semantics)) {
            String fromSemantics = resolveColumn(semantics.primaryMetric(), registryMetrics, false);
            if (fromSemantics != null && registryContains(registryMetrics, fromSemantics)) {
                return fromSemantics;
            }
        }
        if (semantics.relationship() == AnalyticalRelationship.SHARE_OF_TOTAL) {
            String fromSemantics = resolveColumn(semantics.primaryMetric(), registryMetrics, false);
            if (fromSemantics != null && registryContains(registryMetrics, fromSemantics)) {
                return fromSemantics;
            }
        }
        if (schema.resolved() && registryContains(registryMetrics, schema.primaryColumn())) {
            return bareColumnMatch(schema.primaryColumn(), registryMetrics);
        }
        String fromSemantics = resolveColumn(semantics.primaryMetric(), registryMetrics, false);
        if (fromSemantics != null && registryContains(registryMetrics, fromSemantics)) {
            return fromSemantics;
        }
        if (schema.resolved()) {
            return schema.primaryColumn();
        }
        return resolveColumn(semantics.primaryMetric(), registryMetrics, true);
    }

    private String chooseTarget(
            QuestionSemantics semantics, SchemaDrivenMetricResult schema, Set<String> registryMetrics
    ) {
        if (isRelationshipQuestion(semantics)) {
            String fromSemantics = resolveColumn(semantics.targetMetric(), registryMetrics, false);
            if (fromSemantics != null && registryContains(registryMetrics, fromSemantics)) {
                return fromSemantics;
            }
        }
        if (semantics.relationship() == AnalyticalRelationship.SHARE_OF_TOTAL) {
            String fromSemantics = resolveColumn(semantics.targetMetric(), registryMetrics, false);
            if (fromSemantics != null && registryContains(registryMetrics, fromSemantics)) {
                return fromSemantics;
            }
        }
        if (schema.secondaryColumn() != null
                && registryContains(registryMetrics, schema.secondaryColumn())) {
            return bareColumnMatch(schema.secondaryColumn(), registryMetrics);
        }
        return resolveColumn(semantics.targetMetric(), registryMetrics, false);
    }

    private String alternateRelationshipMetric(
            QuestionSemantics semantics,
            SchemaDrivenMetricResult schema,
            String primary,
            Set<String> registryMetrics
    ) {
        if (schema.secondaryColumn() != null && !schema.secondaryColumn().equalsIgnoreCase(primary)) {
            return bareColumnMatch(schema.secondaryColumn(), registryMetrics);
        }
        for (String candidate : List.of(semantics.targetMetric(), semantics.primaryMetric())) {
            String resolved = resolveColumn(candidate, registryMetrics, false);
            if (resolved != null && !resolved.equalsIgnoreCase(primary)) {
                return resolved;
            }
        }
        return registryMetrics.stream()
                .map(SemanticCatalogBuilder::bareColumn)
                .filter(k -> !k.equalsIgnoreCase(primary))
                .findFirst()
                .orElse(null);
    }

    private void logMetricDebug(String question, SchemaDrivenMetricResult result) {
        if (result.debug() == null) return;
        var d = result.debug();
        log.info("[metric-resolution-debug] question={}", question);
        log.info("[metric-resolution-debug] extracted_phrases={}", d.extractedPhrases());
        log.info("[metric-resolution-debug] candidate_metrics={}",
                d.candidates().stream().map(MetricMatchCandidate::columnName).toList());
        for (var c : d.candidates()) {
            log.info("[metric-resolution-debug] candidate_score column={} score={} accepted={} match={} kind={}",
                    c.columnName(), String.format("%.3f", c.score()), c.accepted(),
                    c.matchedPhrase(), c.matchKind());
        }
        if (d.winner() != null) {
            log.info("[metric-resolution-debug] winning_metric={} score={}",
                    d.winner().columnName(), String.format("%.3f", d.winner().score()));
        } else {
            log.info("[metric-resolution-debug] winning_metric=UNRESOLVED");
        }
        String secondary = result.secondaryColumn() != null ? result.secondaryColumn() : "none";
        log.info("[metric-resolution-debug] secondary_metric={}", secondary);
        log.info("[metric-resolution-debug] rejected_candidates={}",
                d.rejected().stream()
                        .map(c -> c.columnName() + "=" + String.format("%.3f", c.score()))
                        .toList());
        log.info("[metric-resolution-debug] reason={}", d.selectionReason());
    }

    private String resolveColumn(String requested, Set<String> registryKeys, boolean required) {
        if (requested != null && !requested.isBlank()) {
            if (registryKeys.isEmpty()) return requested;
            if (registryContains(registryKeys, requested)) {
                return bareColumnMatch(requested, registryKeys);
            }
            String fuzzy = fuzzyMatch(requested, registryKeys);
            if (fuzzy != null) return fuzzy;
        }
        if (!required) return null;
        return requested;
    }

    private String bareColumnMatch(String requested, Set<String> keys) {
        for (String k : keys) {
            if (SemanticCatalogBuilder.bareColumn(k).equalsIgnoreCase(requested)) {
                return SemanticCatalogBuilder.bareColumn(k);
            }
        }
        return requested;
    }

    private boolean registryContains(Set<String> keys, String column) {
        if (column == null) return false;
        for (String k : keys) {
            if (k.equalsIgnoreCase(column)
                    || SemanticCatalogBuilder.bareColumn(k).equalsIgnoreCase(column)) {
                return true;
            }
        }
        return false;
    }

    private String fuzzyMatch(String requested, Set<String> keys) {
        String norm = requested.toLowerCase(Locale.ROOT);
        String best = null;
        int bestScore = 0;
        for (String k : keys) {
            String bare = SemanticCatalogBuilder.bareColumn(k).toLowerCase(Locale.ROOT);
            if (bare.equals(norm)) return SemanticCatalogBuilder.bareColumn(k);
            if (bare.contains(norm) || norm.contains(bare)) {
                int score = Math.min(bare.length(), norm.length());
                if (score > bestScore) {
                    bestScore = score;
                    best = SemanticCatalogBuilder.bareColumn(k);
                }
            }
            String humanized = bare.replace('_', ' ');
            if (humanized.contains(norm) || norm.contains(humanized)) {
                int score = Math.min(humanized.length(), norm.length());
                if (score > bestScore) {
                    bestScore = score;
                    best = SemanticCatalogBuilder.bareColumn(k);
                }
            }
        }
        return best;
    }

    private Set<String> metricKeys(RegistryResolutionBundle bundle) {
        Set<String> keys = new HashSet<>();
        if (bundle != null && bundle.metrics() != null) {
            for (MetricDescriptor m : bundle.metrics()) keys.add(m.key());
        }
        return keys;
    }

    private Set<String> dimensionKeys(RegistryResolutionBundle bundle) {
        Set<String> keys = new HashSet<>();
        if (bundle != null && bundle.dimensions() != null) {
            for (DimensionDescriptor d : bundle.dimensions()) keys.add(d.key());
        }
        return keys;
    }

    private boolean isDistanceColumn(String col) {
        return col != null && (col.contains("distance") || col.contains("mile"));
    }

    private String bucketize(String dimension, RegistryResolutionBundle bundle) {
        if (dimension.endsWith("_flag") || dimension.endsWith("_bucket")) return dimension;
        if (isTemporalDimension(dimension, bundle)) return dimension;
        if (isNumericDimension(dimension, bundle)) return dimension + "_bucket";
        return dimension;
    }

    private boolean isTemporalDimension(String col, RegistryResolutionBundle bundle) {
        if (bundle == null || bundle.dimensions() == null) return false;
        return bundle.dimensions().stream()
                .filter(d -> SemanticCatalogBuilder.bareColumn(d.key()).equalsIgnoreCase(col))
                .anyMatch(d -> "TEMPORAL".equalsIgnoreCase(d.type()));
    }

    private boolean isNumericDimension(String col, RegistryResolutionBundle bundle) {
        if (bundle == null || bundle.dimensions() == null) return false;
        return bundle.dimensions().stream()
                .filter(d -> SemanticCatalogBuilder.bareColumn(d.key()).equalsIgnoreCase(col))
                .anyMatch(d -> "NUMERIC".equalsIgnoreCase(d.type()));
    }
}
