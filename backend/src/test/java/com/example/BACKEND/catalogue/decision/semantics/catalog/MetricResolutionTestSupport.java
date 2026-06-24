package com.example.BACKEND.catalogue.decision.semantics.catalog;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticRegistry;
import com.example.BACKEND.catalogue.decision.planning.DomainAnalyticalDefaults;
import com.example.BACKEND.catalogue.decision.semantic.QueryEntityResolver;
import com.example.BACKEND.catalogue.decision.semantic.SemanticDictionary;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.catalogue.decision.semantics.RelationshipIntentDetector;

import java.util.List;

/**
 * Wires schema-driven metric resolution components for tests (no Spring context).
 */
public final class MetricResolutionTestSupport {

    private MetricResolutionTestSupport() {}

    static final CatalogQuestionMatcher MATCHER = new CatalogQuestionMatcher();
    static final SemanticCatalogBuilder CATALOG_BUILDER = new SemanticCatalogBuilder();
    static final QuestionSlotExtractor SLOT_EXTRACTOR = new QuestionSlotExtractor();
    static final SchemaDrivenMetricResolver SCHEMA_RESOLVER = new SchemaDrivenMetricResolver(
            CATALOG_BUILDER, MATCHER, SLOT_EXTRACTOR);

    public static QuestionSemanticExtractor extractor() {
        return new QuestionSemanticExtractor(
                new QueryEntityResolver(new SemanticDictionary()),
                new SemanticDictionary(),
                CATALOG_BUILDER,
                MATCHER,
                SCHEMA_RESOLVER,
                new RelationshipIntentDetector());
    }

    public static MetricResolutionEngine engine() {
        return engine(new DomainAnalyticalDefaults());
    }

    public static MetricResolutionEngine engine(DomainAnalyticalDefaults domainDefaults) {
        return new MetricResolutionEngine(
                new MetricSemanticRegistry(domainDefaults),
                SCHEMA_RESOLVER);
    }

    public static RegistryResolutionBundle oilBundle() {
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
                        new DimensionDescriptor(table + ".product_type", table + ".product_type", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".recorded_date", table + ".recorded_date", "TIMESTAMP")
                ),
                null);
    }

    static void printResolution(String question, QuestionSemantics semantics, MetricResolution resolution) {
        System.out.println("\n--- " + question);
        if (resolution.debug() != null) {
            System.out.println("  phrases: " + resolution.debug().extractedPhrases());
            for (var c : resolution.debug().candidates()) {
                System.out.printf("  candidate %-20s score=%.3f accepted=%s match=%s%n",
                        c.columnName(), c.score(), c.accepted(), c.matchedPhrase());
            }
            if (resolution.debug().winner() != null) {
                System.out.println("  winner: " + resolution.debug().winner().columnName());
            }
            System.out.println("  rejected: " + resolution.debug().rejected().stream()
                    .map(MetricMatchCandidate::columnName).toList());
            System.out.println("  reason: " + resolution.debug().selectionReason());
        }
        System.out.printf("  primary=%s secondary=%s usable=%s confidence=%.2f%n",
                resolution.primaryMetric(), resolution.targetMetric(),
                resolution.isUsable(), resolution.confidence());
        System.out.printf("  extractor primary=%s target=%s%n",
                semantics.primaryMetric(), semantics.targetMetric());
    }
}
