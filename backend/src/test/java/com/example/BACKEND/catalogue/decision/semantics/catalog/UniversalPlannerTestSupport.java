package com.example.BACKEND.catalogue.decision.semantics.catalog;

import com.example.BACKEND.catalogue.decision.investigation.DimensionResolver;
import com.example.BACKEND.catalogue.decision.investigation.InvestigationStepPlanner;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigationPlanner;
import com.example.BACKEND.catalogue.decision.planning.UniversalAnalysisPlanner;
import com.example.BACKEND.catalogue.decision.semantic.QueryEntityResolver;
import com.example.BACKEND.catalogue.decision.semantic.SemanticDictionary;
import com.example.BACKEND.catalogue.decision.semantics.RelationshipIntentDetector;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SchemaDrivenQuestionResolver;

/**
 * Wires universal analysis planner components for tests (no Spring context).
 */
public final class UniversalPlannerTestSupport {

    private UniversalPlannerTestSupport() {}

    public static QuestionInvestigationPlanner investigationPlanner() {
        CatalogQuestionMatcher matcher = new CatalogQuestionMatcher();
        return new QuestionInvestigationPlanner(
                MetricResolutionTestSupport.extractor(),
                new QueryEntityResolver(new SemanticDictionary()),
                new DimensionResolver(matcher),
                new InvestigationStepPlanner(),
                new SemanticCatalogBuilder(),
                new SchemaDrivenQuestionResolver(matcher),
                new RelationshipIntentDetector());
    }

    public static UniversalAnalysisPlanner universalPlanner() {
        return new UniversalAnalysisPlanner(
                investigationPlanner(),
                new RelationshipIntentDetector());
    }
}
