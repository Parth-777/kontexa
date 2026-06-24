package com.example.BACKEND.catalogue.semantic.phase2.completion;

import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;

/**
 * Deterministic enrichment applied to GPT semantic plans before CQM adaptation.
 */
public interface SemanticPlanEnrichment {

    boolean supports(StructuredSemanticPlan plan);

    StructuredSemanticPlan complete(StructuredSemanticPlan plan, ApprovedCatalogueSnapshot catalogue);
}
