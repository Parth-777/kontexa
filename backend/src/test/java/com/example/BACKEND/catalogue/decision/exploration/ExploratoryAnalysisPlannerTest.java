package com.example.BACKEND.catalogue.decision.exploration;

import com.example.BACKEND.catalogue.decision.clarification.ResolvedAnalyticalQuestion;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.IntentResolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExploratoryAnalysisPlannerTest {

    private ExploratoryAnalysisPlanner planner;

    @BeforeEach
    void setUp() {
        var dictionary = new com.example.BACKEND.catalogue.decision.semantic.SemanticDictionary();
        var entityResolver = new com.example.BACKEND.catalogue.decision.semantic.QueryEntityResolver(dictionary);
        var contributionParser = new com.example.BACKEND.catalogue.decision.semantic.ContributionQuestionParser(entityResolver);
        var dimensionParser = new com.example.BACKEND.catalogue.decision.semantic.DimensionImpactParser(entityResolver);
        var semanticParser = new com.example.BACKEND.catalogue.decision.semantic.SemanticAnalyticalParser(
                entityResolver, contributionParser, dimensionParser);
        var heuristics = new FallbackAnalyticalHeuristics(
                new SemanticFallbackDictionary(), entityResolver);
        var ambiguity = new com.example.BACKEND.catalogue.decision.clarification.AmbiguityDetector(
                new com.example.BACKEND.catalogue.decision.clarification.DomainOntology(
                        new com.example.BACKEND.catalogue.decision.planning.DomainAnalyticalDefaults()));
        var intentClassifier = new com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentClassifier();
        var candidateEngine = new MultiCandidateInterpretationEngine(
                semanticParser, heuristics, ambiguity, intentClassifier);
        var analysisGenerator = new com.example.BACKEND.catalogue.decision.candidate.CandidateAnalysisGenerator(
                semanticParser, heuristics, candidateEngine, entityResolver);
        var softValidator = new SoftSemanticValidator(
                new com.example.BACKEND.catalogue.decision.clarification.QueryViabilityChecker(
                        new com.example.BACKEND.catalogue.decision.clarification.MetricFallbackHierarchy()),
                new com.example.BACKEND.catalogue.decision.clarification.MetricFallbackHierarchy());
        var metricRegistry = new com.example.BACKEND.catalogue.decision.governance.MetricSemanticRegistry(
                new com.example.BACKEND.catalogue.decision.planning.DomainAnalyticalDefaults());
        planner = new ExploratoryAnalysisPlanner(
                candidateEngine, analysisGenerator, softValidator,
                new com.example.BACKEND.catalogue.decision.clarification.MetricFallbackHierarchy(),
                metricRegistry);
    }

    @Test
    void weekendQuestion_alwaysViableWithCandidates() {
        IntentResolution intent = new IntentResolution(
                UUID.randomUUID(), "tenant", "How do weekend rides contribute to revenue?", "GENERAL", 0.7);
        ResolvedAnalyticalQuestion resolved = planner.plan(intent, null);

        assertTrue(resolved.viable());
        assertFalse(resolved.candidateInterpretations().isEmpty());
        assertTrue(resolved.candidateInterpretations().size() >= 2);
    }

    @Test
    void weakQuestion_stillExecutesWithExplorationNote() {
        IntentResolution intent = new IntentResolution(
                UUID.randomUUID(), "tenant", "How does something vague affect numbers?", "GENERAL", 0.5);
        ResolvedAnalyticalQuestion resolved = planner.plan(intent, null);

        assertTrue(resolved.viable());
        assertFalse(resolved.assumption().primaryMetric().isBlank());
    }
}
