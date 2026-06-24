package com.example.BACKEND.catalogue.decision.semantics.catalog;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionDebug;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema-driven metric resolution: slot extraction, ranked catalog matching, debug trace.
 * No taxi/revenue fallbacks — unresolved when catalog cannot match.
 */
@Component
public class SchemaDrivenMetricResolver {

    private static final Logger log = LoggerFactory.getLogger(SchemaDrivenMetricResolver.class);

    private final SemanticCatalogBuilder catalogBuilder;
    private final CatalogQuestionMatcher catalogMatcher;
    private final QuestionSlotExtractor slotExtractor;

    public SchemaDrivenMetricResolver(
            SemanticCatalogBuilder catalogBuilder,
            CatalogQuestionMatcher catalogMatcher,
            QuestionSlotExtractor slotExtractor
    ) {
        this.catalogBuilder = catalogBuilder;
        this.catalogMatcher = catalogMatcher;
        this.slotExtractor = slotExtractor;
    }

    public SchemaDrivenMetricResult resolve(String question, RegistryResolutionBundle bundle) {
        if (question == null || question.isBlank()) {
            return SchemaDrivenMetricResult.unresolved(
                    MetricResolutionDebug.empty("Empty question"));
        }

        SemanticCatalog catalog = catalogBuilder.build(bundle);
        if (!catalog.hasSchema()) {
            return SchemaDrivenMetricResult.unresolved(
                    MetricResolutionDebug.empty("No metrics in registry catalog"));
        }

        QuestionMetricSlots slots = slotExtractor.extract(question);
        List<String> extractedPhrases = slotExtractor.extractedPhrases(slots);

        List<MetricMatchCandidate> ranked = rankAllCandidates(question, slots, catalog);
        MetricMatchCandidate winner = pickWinner(question, slots, catalog);
        MetricMatchCandidate secondary = pickSecondary(question, slots, catalog, winner);

        List<MetricMatchCandidate> rejected = ranked.stream()
                .filter(c -> winner == null || !c.columnName().equals(winner.columnName()))
                .filter(c -> secondary == null || !c.columnName().equals(secondary.columnName()))
                .filter(c -> !c.accepted())
                .toList();

        String reason = buildSelectionReason(slots, winner, secondary);
        MetricResolutionDebug debug = new MetricResolutionDebug(
                extractedPhrases, ranked, winner, rejected, reason);

        log.debug("[metric-resolution] question={} phrases={} winner={} secondary={} reason={}",
                question, extractedPhrases,
                winner != null ? winner.columnName() : "UNRESOLVED",
                secondary != null ? secondary.columnName() : "none",
                reason);
        log.info("[metric-resolution] question={} winner={} score={} candidates={} rejected={}",
                question,
                winner != null ? winner.columnName() : "UNRESOLVED",
                winner != null ? String.format("%.3f", winner.score()) : "0",
                ranked.size(), rejected.size());

        return new SchemaDrivenMetricResult(
                winner != null && winner.accepted() ? winner.columnName() : null,
                winner != null && winner.accepted() ? winner.label() : null,
                secondary != null && secondary.accepted() ? secondary.columnName() : null,
                secondary != null && secondary.accepted() ? secondary.label() : null,
                winner != null ? winner.score() : 0,
                ranked,
                debug);
    }

    private List<MetricMatchCandidate> rankAllCandidates(
            String question, QuestionMetricSlots slots, SemanticCatalog catalog
    ) {
        Map<String, MetricMatchCandidate> merged = new LinkedHashMap<>();
        for (String phrase : slots.allPhrases()) {
            merge(merged, catalogMatcher.rankMetrics(question, phrase, catalog));
        }
        merge(merged, catalogMatcher.rankMetrics(question, null, catalog));
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(MetricMatchCandidate::score).reversed())
                .toList();
    }

    private void merge(Map<String, MetricMatchCandidate> merged, List<MetricMatchCandidate> batch) {
        for (MetricMatchCandidate c : batch) {
            merged.merge(c.columnName(), c, (a, b) -> a.score() >= b.score() ? a : b);
        }
    }

    private MetricMatchCandidate pickWinner(
            String question, QuestionMetricSlots slots, SemanticCatalog catalog
    ) {
        if (slots.primaryPhrase() != null && !slots.primaryPhrase().isBlank()) {
            List<MetricMatchCandidate> ranked =
                    catalogMatcher.rankMetrics(question, slots.primaryPhrase(), catalog);
            MetricMatchCandidate fromSlot = firstAccepted(ranked);
            if (fromSlot != null) return fromSlot;
            if (isRelationshipSlot(slots.kind()) && !ranked.isEmpty()) {
                return ranked.getFirst();
            }
        }
        return firstAccepted(catalogMatcher.rankMetrics(question, null, catalog));
    }

    private MetricMatchCandidate pickSecondary(
            String question, QuestionMetricSlots slots, SemanticCatalog catalog,
            MetricMatchCandidate winner
    ) {
        if (slots.secondaryPhrase() == null || slots.secondaryPhrase().isBlank()) {
            return null;
        }
        String winnerCol = winner != null ? winner.columnName() : null;
        List<MetricMatchCandidate> ranked =
                catalogMatcher.rankMetrics(question, slots.secondaryPhrase(), catalog);
        return ranked.stream()
                .filter(MetricMatchCandidate::accepted)
                .filter(c -> winnerCol == null || !winnerCol.equals(c.columnName()))
                .findFirst()
                .orElseGet(() -> isRelationshipSlot(slots.kind())
                        ? ranked.stream()
                                .filter(c -> winnerCol == null || !winnerCol.equals(c.columnName()))
                                .findFirst()
                                .orElse(null)
                        : null);
    }

    private boolean isRelationshipSlot(QuestionMetricSlots.SlotKind kind) {
        return kind == QuestionMetricSlots.SlotKind.AFFECT_OUTCOME
                || kind == QuestionMetricSlots.SlotKind.CORRELATION_B
                || kind == QuestionMetricSlots.SlotKind.DRIVE_OUTCOME;
    }

    private MetricMatchCandidate firstAccepted(List<MetricMatchCandidate> ranked) {
        return ranked.stream().filter(MetricMatchCandidate::accepted).findFirst().orElse(null);
    }

    private String buildSelectionReason(
            QuestionMetricSlots slots, MetricMatchCandidate winner, MetricMatchCandidate secondary
    ) {
        if (winner == null) {
            return "No metric scored >= threshold for slot kind " + slots.kind();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("slot=").append(slots.kind());
        if (slots.primaryPhrase() != null) {
            sb.append(" primaryPhrase=\"").append(slots.primaryPhrase()).append("\"");
        }
        sb.append(" → ").append(winner.columnName())
                .append(" (").append(winner.matchKind()).append(", score=")
                .append(String.format("%.3f", winner.score())).append(")");
        if (secondary != null) {
            sb.append("; secondary=").append(secondary.columnName());
        }
        return sb.toString();
    }

    public record SchemaDrivenMetricResult(
            String primaryColumn,
            String primaryLabel,
            String secondaryColumn,
            String secondaryLabel,
            double confidence,
            List<MetricMatchCandidate> candidates,
            MetricResolutionDebug debug
    ) {
        public boolean resolved() {
            return primaryColumn != null && !primaryColumn.isBlank();
        }

        static SchemaDrivenMetricResult unresolved(MetricResolutionDebug debug) {
            return new SchemaDrivenMetricResult(
                    null, null, null, null, 0, List.of(), debug);
        }
    }
}
