package com.example.BACKEND.catalogue.decision.candidate;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet;
import com.example.BACKEND.catalogue.decision.exploration.AnalyticalExplorationPolicy;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Executes all viable analytical candidates in parallel, scores outputs, selects strongest evidence.
 *
 * Architecture: interpret → execute candidates → validate outputs → synthesize
 */
@Service
public class CandidateExecutionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CandidateExecutionOrchestrator.class);

    public static final String STRONGEST_MATCH_NOTE =
            "Using the strongest matching analytical interpretation.";

    public record SelectionResult(
            CandidateResultScorer.ScoredCandidate winner,
            List<CandidateResultScorer.ScoredCandidate> ranked,
            String selectionNote
    ) {
        public boolean hasWinner() {
            return winner != null && winner.totalScore() > 0
                    && winner.result() != null && winner.result().hasContent();
        }

        public MaterializedQueryResult winningMaterialization() {
            return hasWinner() ? winner.result() : MaterializedQueryResult.empty();
        }
    }

    private final CandidateMaterializationExecutor executor;
    private final CandidateResultScorer scorer;

    public CandidateExecutionOrchestrator(
            CandidateMaterializationExecutor executor,
            CandidateResultScorer scorer
    ) {
        this.executor = executor;
        this.scorer = scorer;
    }

    public SelectionResult executeAndSelect(
            ComputationResultSet results,
            List<AnalyticalCandidate> candidates
    ) {
        return executeAndSelect(results, candidates, null);
    }

    public SelectionResult executeAndSelect(
            ComputationResultSet results,
            List<AnalyticalCandidate> candidates,
            MetricResolution expectedResolution
    ) {
        List<Map<String, Object>> rows = mergeRows(results);
        if (rows == null || rows.isEmpty() || candidates == null || candidates.isEmpty()) {
            return new SelectionResult(null, List.of(), "");
        }

        List<CompletableFuture<CandidateResultScorer.ScoredCandidate>> futures = candidates.stream()
                .map(c -> CompletableFuture.supplyAsync(() -> {
                    MaterializedQueryResult mat = executor.execute(rows, c);
                    return scorer.score(c, mat, expectedResolution);
                }))
                .toList();

        List<CandidateResultScorer.ScoredCandidate> scored = futures.stream()
                .map(CompletableFuture::join)
                .filter(s -> s.result() != null && s.result().hasContent())
                .sorted(Comparator.comparingDouble(CandidateResultScorer.ScoredCandidate::totalScore).reversed())
                .collect(Collectors.toCollection(ArrayList::new));

        for (int i = 0; i < Math.min(scored.size(), 5); i++) {
            var s = scored.get(i);
            log.info("[candidate-exec] #{} {} score={} groups={} rows={}",
                    i + 1, s.candidate().label(),
                    String.format(Locale.ROOT, "%.3f", s.totalScore()),
                    s.result().primaryGrouping() != null
                            ? s.result().primaryGrouping().groupCount() : 0,
                    s.result().totalRows());
        }

        if (scored.isEmpty()) {
            log.info("[candidate-exec] no candidate produced grouped evidence");
            return new SelectionResult(null, List.of(), "");
        }

        CandidateResultScorer.ScoredCandidate winner = scored.getFirst();
        String note = STRONGEST_MATCH_NOTE;
        if (candidates.size() > 1) {
            note = STRONGEST_MATCH_NOTE + " Selected: " + winner.candidate().label() + ".";
        }

        return new SelectionResult(winner, scored, note);
    }

    private List<Map<String, Object>> mergeRows(ComputationResultSet resultSet) {
        if (resultSet == null || resultSet.results().isEmpty()) return List.of();
        List<Map<String, Object>> merged = new ArrayList<>();
        for (var qr : resultSet.results()) {
            if (qr.rows() != null) merged.addAll(qr.rows());
        }
        return merged;
    }
}
