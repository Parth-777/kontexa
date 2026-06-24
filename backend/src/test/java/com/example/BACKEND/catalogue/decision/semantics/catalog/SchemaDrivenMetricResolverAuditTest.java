package com.example.BACKEND.catalogue.decision.semantics.catalog;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Before/after audit for schema-driven metric resolution.
 * Run: mvn test -Dtest=SchemaDrivenMetricResolverAuditTest
 */
class SchemaDrivenMetricResolverAuditTest {

    private final QuestionSemanticExtractor extractor = MetricResolutionTestSupport.extractor();
    private final MetricResolutionEngine engine = MetricResolutionTestSupport.engine();
    private final RegistryResolutionBundle bundle = MetricResolutionTestSupport.oilBundle();

    static Stream<Arguments> auditCases() {
        return Stream.of(
                Arguments.of(
                        "Which oil field generates the highest profit?",
                        "profit_margin", null, true),
                Arguments.of(
                        "How does downtime affect profitability?",
                        "profit_margin", "downtime_hours", true),
                Arguments.of(
                        "Which facility type is most profitable?",
                        "profit_margin", null, true),
                Arguments.of(
                        "What drives profitability?",
                        "profit_margin", null, true),
                Arguments.of(
                        "Does carbon emission correlate with profit margin?",
                        "profit_margin", "carbon_emission", true)
        );
    }

    @ParameterizedTest
    @MethodSource("auditCases")
    void resolvesMetricsFromSchemaOnly(
            String question, String expectedPrimary, String expectedSecondary, boolean usable
    ) {
        QuestionSemantics semantics = extractor.extract(question, bundle);
        MetricResolution resolution = engine.resolve(semantics, bundle);
        MetricResolutionTestSupport.printResolution(question, semantics, resolution);

        assertEquals(expectedPrimary, resolution.primaryMetric(), "primary metric for: " + question);
        if (expectedSecondary != null) {
            assertEquals(expectedSecondary, resolution.targetMetric(), "secondary metric for: " + question);
        }
        assertEquals(usable, resolution.isUsable());
        assertNotNull(resolution.debug());
        assertNotNull(resolution.debug().winner());
        assertTrue(resolution.candidates().size() >= 1);
        assertTrue(resolution.debug().candidates().stream()
                .anyMatch(c -> c.columnName().equals(expectedPrimary)));
    }

    @ParameterizedTest
    @MethodSource("auditCases")
    void noTaxiFallbackColumns(String question) {
        MetricResolution resolution = engine.resolve(
                extractor.extract(question, bundle), bundle);
        assertTrue(resolution.primaryMetric() == null
                || !"total_amount".equals(resolution.primaryMetric()),
                "must not fall back to taxi total_amount for: " + question);
    }
}
