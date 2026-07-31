package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resolves an {@link InvestigationObjective} contrast into executable slice predicates.
 */
@Component
public class ContrastResolver {

    private final SlicePredicateBuilder predicateBuilder;
    private final CandidateDimensionEnumerator dimensionEnumerator;

    public ContrastResolver(SlicePredicateBuilder predicateBuilder,
                            CandidateDimensionEnumerator dimensionEnumerator) {
        this.predicateBuilder = predicateBuilder;
        this.dimensionEnumerator = dimensionEnumerator;
    }

    public ResolvedInvestigation resolve(String question,
                                         String tenantId,
                                         String tableRef,
                                         InvestigationObjective objective,
                                         RegistryResolutionBundle bundle) {
        InvestigationObjective.Comparison comparison = objective.comparison();
        SlicePredicateBuilder.BuiltSlice focalBuilt = predicateBuilder.build(
                comparison.focal(), objective.scope().filters(), tableRef, objective.measure());
        SlicePredicateBuilder.BuiltSlice baselineBuilt = predicateBuilder.build(
                comparison.baseline(), objective.scope().filters(), tableRef, objective.measure());

        Set<String> contrastColumns = new LinkedHashSet<>();
        contrastColumns.addAll(focalBuilt.columns());
        contrastColumns.addAll(baselineBuilt.columns());
        if (objective.decomposition() != null && objective.decomposition().exclude() != null) {
            contrastColumns.addAll(objective.decomposition().exclude());
        }

        String contrastDescription = "focal[" + focalBuilt.label() + "] vs baseline["
                + baselineBuilt.label() + "]";

        var candidates = dimensionEnumerator.enumerate(bundle, objective, contrastColumns);

        return new ResolvedInvestigation(
                question,
                tenantId,
                tableRef,
                objective,
                new SlicePredicate(focalBuilt.sqlPredicate(), focalBuilt.label()),
                new SlicePredicate(baselineBuilt.sqlPredicate(), baselineBuilt.label()),
                contrastDescription,
                contrastColumns,
                candidates);
    }
}
