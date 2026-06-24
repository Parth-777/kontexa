package com.example.BACKEND.catalogue.decision.semantic;

import com.example.BACKEND.catalogue.decision.validation.SemanticParserBenchmarkSuite;
import com.example.BACKEND.catalogue.decision.validation.SemanticParserValidationHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticAnalyticalParserTest {

    private SemanticParserValidationHarness harness;

    @BeforeEach
    void setUp() {
        SemanticDictionary dictionary = new SemanticDictionary();
        QueryEntityResolver entityResolver = new QueryEntityResolver(dictionary);
        ContributionQuestionParser contributionParser = new ContributionQuestionParser(entityResolver);
        DimensionImpactParser dimensionParser = new DimensionImpactParser(entityResolver);
        SemanticAnalyticalParser parser = new SemanticAnalyticalParser(
                entityResolver, contributionParser, dimensionParser);
        harness = new SemanticParserValidationHarness(parser);
    }

    @Test
    void benchmarkSuite_allCasesPass() {
        SemanticParserValidationHarness.SuiteReport report = harness.runAll();
        for (var result : report.results()) {
            assertTrue(result.passed(),
                    () -> result.id() + " " + result.question() + " failures: " + result.failures());
        }
        assertEquals(SemanticParserBenchmarkSuite.all().size(), report.passed());
    }

    @Test
    void tipContribution_resolvesAsCompositionRatio() {
        var plan = harness.runCase(SemanticParserBenchmarkSuite.all().getFirst());
        assertTrue(plan.passed(), plan.failures().toString());
        assertTrue((Boolean) plan.resolved().get("parsed"));
        assertEquals("tip_amount", plan.resolved().get("primaryMetric"));
    }

    @Test
    void tripDistanceImpact_resolvesDimensionBucket() {
        var plan = harness.runCase(SemanticParserBenchmarkSuite.all().get(5));
        assertTrue(plan.passed(), plan.failures().toString());
        assertEquals("trip_distance_bucket", plan.resolved().get("grouping"));
    }
}
