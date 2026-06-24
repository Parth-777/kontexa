package com.example.BACKEND.catalogue.decision.findings;

import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedGroupEntry;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.ComparativeFinding;
import com.example.BACKEND.catalogue.decision.grounding.PresentationLabelResolver;
import com.example.BACKEND.catalogue.decision.grounding.SemanticRelationshipValidator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ComparativeFindingProducer {

    private final PresentationLabelResolver labels;
    private final SemanticRelationshipValidator validator;

    public ComparativeFindingProducer(
            PresentationLabelResolver labels,
            SemanticRelationshipValidator validator
    ) {
        this.labels = labels;
        this.validator = validator;
    }

    public List<ComparativeFinding> produce(MaterializedGrouping grouping, String metricLabel) {
        List<ComparativeFinding> comparisons = new ArrayList<>();
        if (grouping == null || !grouping.hasData()) return comparisons;

        String resolvedMetric = labels.resolveMetric(metricLabel);
        List<MaterializedGroupEntry> entries = grouping.rankedEntries();
        if (entries.size() < 2) return comparisons;

        MaterializedGroupEntry leader = entries.get(0);
        MaterializedGroupEntry second = entries.get(1);
        MaterializedGroupEntry tail   = entries.get(entries.size() - 1);

        buildComparison(leader, second, resolvedMetric).ifPresent(comparisons::add);

        if (entries.size() > 2) {
            buildComparison(leader, tail, resolvedMetric).ifPresent(comparisons::add);
        }

        return comparisons;
    }

    private java.util.Optional<ComparativeFinding> buildComparison(
            MaterializedGroupEntry a,
            MaterializedGroupEntry b,
            String metric
    ) {
        if (labels.isInternalKey(a.entityKey()) || labels.isInternalKey(b.entityKey())) {
            return java.util.Optional.empty();
        }

        double delta    = a.totalValue() - b.totalValue();
        double deltaPct = b.totalValue() > 0 ? (delta / b.totalValue()) * 100 : 0;
        double multiple = b.totalValue() > 0 ? a.totalValue() / b.totalValue() : a.totalValue();

        String direction = delta > 1.0 ? "A_LEADS"
                : delta < -1.0 ? "B_LEADS"
                : "PARITY";

        ComparativeFinding draft = new ComparativeFinding(
                labels.resolveSegment(a.entityKey()),
                labels.resolveSegment(b.entityKey()),
                a.totalValue(), b.totalValue(),
                delta, deltaPct, direction, multiple,
                metric, ""
        );

        return java.util.Optional.of(new ComparativeFinding(
                draft.entityA(), draft.entityB(),
                draft.valueA(), draft.valueB(),
                draft.delta(), draft.deltaPct(),
                draft.direction(), draft.multiple(),
                draft.metricLabel(),
                validator.correctComparativeSummary(draft)
        ));
    }
}
