package com.example.BACKEND.catalogue.decision.calibration;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Analyses query intent and evidence signals to produce complexity scores
 * that drive mode selection in {@link ResponseCalibrationEngine}.
 *
 * Signal sources:
 *   1. Question text markers (lexical patterns for question type)
 *   2. Materiality score from ranked evidence
 *   3. Investigation tree depth (proxy for structural complexity)
 *   4. Anomaly count in comparative contexts
 *   5. Evidence confidence
 *
 * This is deterministic — no LLM involved.
 */
@Component
public class QueryComplexityAnalyser {

    // ─── question-type markers ──────────────────────────────────────────

    private static final String[] FACTUAL_MARKERS = {
            "how much", "what is", "what are", "what percentage", "what %",
            "how many", "what was", "total", "contribution", "contribute",
            "count", "number of", "average", "mean", "sum", "revenue of",
            "value of", "share of", "proportion", "ratio", "rate of"
    };

    private static final String[] ANALYTICAL_MARKERS = {
            "rank", "ranking", "top", "bottom", "compare", "comparison",
            "versus", " vs ", "trend", "segment", "breakdown", "split",
            "distribution", "which", "best", "worst", "highest", "lowest",
            "by region", "by segment", "by category", "by channel", "by type"
    };

    private static final String[] INVESTIGATIVE_MARKERS = {
            "why", "what caused", "reason", "decline", "drop", "fell",
            "decrease", "anomaly", "unusual", "unexpected", "investigate",
            "problem", "issue", "concern", "root cause", "driving", "driver",
            "behind", "explain", "what happened", "went wrong"
    };

    private static final String[] STRATEGIC_MARKERS = {
            "strategy", "strategic", "risk", "recommend", "recommendation",
            "opportunity", "growth plan", "investment", "priorit",
            "outlook", "forecast", "roadmap", "implications", "impact",
            "should we", "what should", "next quarter", "next year",
            "executive", "board", "leadership"
    };

    // ─── complexity signals record ──────────────────────────────────────

    /**
     * Raw complexity signals extracted before mode determination.
     */
    public record ComplexitySignals(
            boolean isLikelyFactual,
            boolean isLikelyAnalytical,
            boolean isLikelyInvestigative,
            boolean isLikelyStrategic,
            double  topMaterialityScore,
            int     investigationDepth,
            int     anomalyCount,
            double  averageConfidence
    ) {}

    // ─── public API ─────────────────────────────────────────────────────

    public ComplexitySignals analyse(IntentResolution intent, List<RankedEvidence> ranked) {
        String question = intent.question().toLowerCase(Locale.ROOT);

        boolean factual      = matchesAny(question, FACTUAL_MARKERS);
        boolean analytical   = matchesAny(question, ANALYTICAL_MARKERS);
        boolean investigative = matchesAny(question, INVESTIGATIVE_MARKERS);
        boolean strategic    = matchesAny(question, STRATEGIC_MARKERS);

        // If question has mixed signals, analytical overrides factual
        // and investigative overrides analytical
        if (investigative) factual = false;
        if (strategic)     { factual = false; analytical = false; }

        double topScore        = topMaterialityScore(ranked);
        int    depth           = investigationDepth(ranked);
        int    anomalies       = anomalyCount(ranked);
        double avgConfidence   = averageConfidence(ranked);

        // High-materiality evidence upgrades mode regardless of question text
        // e.g. a "what is" question that surfaces a major anomaly → investigative
        if (anomalies >= 3 && !strategic)    investigative = true;
        if (topScore > 0.75 && !strategic)   investigative = true;

        return new ComplexitySignals(
                factual, analytical, investigative, strategic,
                topScore, depth, anomalies, avgConfidence
        );
    }

    // ─── private helpers ────────────────────────────────────────────────

    private boolean matchesAny(String text, String[] markers) {
        for (String m : markers) {
            if (text.contains(m)) return true;
        }
        return false;
    }

    private double topMaterialityScore(List<RankedEvidence> ranked) {
        return ranked.stream()
                .mapToDouble(RankedEvidence::score)
                .max()
                .orElse(0.0);
    }

    private int investigationDepth(List<RankedEvidence> ranked) {
        // Investigation depth is read from EvidenceObject by the calling engine.
        // RankedEvidence only carries the feature vector; return 0 here.
        return 0;
    }

    private int anomalyCount(List<RankedEvidence> ranked) {
        long count = ranked.stream()
                .filter(re -> {
                    Map<String, Double> f = re.featureVector();
                    if (f == null) return false;
                    return f.getOrDefault("anomaly_severity", 0.0) > 0.4
                            || f.getOrDefault("downside_risk", 0.0) > 0.6;
                })
                .count();
        return (int) count;
    }

    private double averageConfidence(List<RankedEvidence> ranked) {
        return ranked.stream()
                .mapToDouble(RankedEvidence::score)
                .average()
                .orElse(0.5);
    }
}
