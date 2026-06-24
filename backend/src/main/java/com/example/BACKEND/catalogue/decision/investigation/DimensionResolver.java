package com.example.BACKEND.catalogue.decision.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.semantics.catalog.CatalogQuestionMatcher;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticCatalog;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticCatalogBuilder;
import com.example.BACKEND.catalogue.decision.transforms.SemanticConcept;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Phase 2 — maps business entities to warehouse dimensions via schema catalog matching.
 */
@Component
public class DimensionResolver {

    private final CatalogQuestionMatcher catalogMatcher;

    public DimensionResolver(CatalogQuestionMatcher catalogMatcher) {
        this.catalogMatcher = catalogMatcher;
    }

    public ResolvedDimension resolve(
            ExtractedQuestionEntities extraction,
            RegistryResolutionBundle bundle,
            String question
    ) {
        return resolve(extraction, bundle, question, null);
    }

    public ResolvedDimension resolve(
            ExtractedQuestionEntities extraction,
            RegistryResolutionBundle bundle,
            String question,
            SemanticCatalog catalog
    ) {
        if (extraction == null) {
            return ResolvedDimension.unresolved(null, "No question entities extracted.");
        }
        if (extraction.isShareAnalysis()) {
            return ResolvedDimension.physical("composition", "composition", "Revenue composition");
        }
        if (extraction.isRelationshipAnalysis()) {
            return ResolvedDimension.relationshipAnalysis(
                    extraction.targetMetricKey(), extraction.targetMetricKey());
        }

        String entityKey = extraction.businessEntityKey();
        String phrase = extraction.businessEntityPhrase();

        if (catalog != null && catalog.hasSchema()) {
            CatalogQuestionMatcher.MatchResult match = entityKey != null && !entityKey.isBlank()
                    ? catalogMatcher.bestDimension(question, entityKey, catalog)
                    : catalogMatcher.bestDimension(question, phrase, catalog);
            if (match.resolved()) {
                return resolveFromCatalog(match, phrase, entityKey, bundle);
            }
        }

        if (entityKey == null || entityKey.isBlank()) {
            return ResolvedDimension.unresolved(
                    phrase,
                    "Could not resolve business entity to a warehouse dimension.");
        }

        if (columnInRegistry(entityKey, bundle)) {
            return resolvePhysical(entityKey, phrase, bundle);
        }

        if (entityKey.endsWith("_bucket")) {
            String base = entityKey.replace("_bucket", "");
            return ResolvedDimension.derived(
                    phrase != null ? phrase : entityKey, base, entityKey,
                    labelFromKey(entityKey), bucketConcept(base));
        }

        return ResolvedDimension.unresolved(
                phrase != null ? phrase : entityKey,
                "Could not resolve business entity \""
                        + (phrase != null ? phrase : entityKey)
                        + "\" to a warehouse dimension.");
    }

    private ResolvedDimension resolveFromCatalog(
            CatalogQuestionMatcher.MatchResult match,
            String phrase,
            String entityKey,
            RegistryResolutionBundle bundle
    ) {
        String col = match.columnName();
        String label = match.label() != null ? match.label() : labelFromKey(col);
        String display = phrase != null && !phrase.isBlank() ? phrase
                : (entityKey != null && !entityKey.isBlank() ? entityKey : col);

        if (isTemporalColumn(col, bundle)) {
            return ResolvedDimension.derived(display, col, col, label, SemanticConcept.IDENTITY);
        }
        if (isNumericBucketColumn(col, bundle)) {
            return ResolvedDimension.derived(
                    display, col, col + "_bucket", label, bucketConcept(col));
        }
        return ResolvedDimension.physical(display, col, label);
    }

    private ResolvedDimension resolvePhysical(String entityKey, String phrase, RegistryResolutionBundle bundle) {
        if (isTemporalColumn(entityKey, bundle)) {
            return ResolvedDimension.derived(
                    phrase != null ? phrase : entityKey, entityKey, entityKey,
                    labelFromKey(entityKey), SemanticConcept.IDENTITY);
        }
        return ResolvedDimension.physical(
                phrase != null ? phrase : entityKey, entityKey, labelFromKey(entityKey));
    }

    private boolean isTemporalColumn(String col, RegistryResolutionBundle bundle) {
        if (bundle == null || bundle.dimensions() == null) return false;
        return bundle.dimensions().stream()
                .filter(d -> SemanticCatalogBuilder.bareColumn(d.key()).equalsIgnoreCase(col))
                .anyMatch(d -> "TEMPORAL".equalsIgnoreCase(d.type()));
    }

    private boolean isNumericBucketColumn(String col, RegistryResolutionBundle bundle) {
        if (bundle == null || bundle.dimensions() == null) return false;
        return bundle.dimensions().stream()
                .filter(d -> SemanticCatalogBuilder.bareColumn(d.key()).equalsIgnoreCase(col))
                .anyMatch(d -> "NUMERIC".equalsIgnoreCase(d.type()));
    }

    private boolean columnInRegistry(String key, RegistryResolutionBundle bundle) {
        if (bundle == null || bundle.dimensions() == null || key == null) return false;
        return bundle.dimensions().stream()
                .anyMatch(d -> SemanticCatalogBuilder.bareColumn(d.key()).equalsIgnoreCase(key)
                        || d.key().equalsIgnoreCase(key));
    }

    private SemanticConcept bucketConcept(String base) {
        if (base.contains("distance")) return SemanticConcept.TRIP_DISTANCE_BUCKET;
        if (base.contains("tip")) return SemanticConcept.TIP_BUCKET;
        if (base.contains("fare")) return SemanticConcept.FARE_BUCKET;
        return SemanticConcept.IDENTITY;
    }

    private String labelFromKey(String key) {
        return key.replace('_', ' ');
    }
}
