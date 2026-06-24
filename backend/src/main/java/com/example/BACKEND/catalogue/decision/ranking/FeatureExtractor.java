package com.example.BACKEND.catalogue.decision.ranking;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts an executive-grade feature vector from an {@link EvidenceObject}.
 *
 * Features are normalised to [0.0, 1.0] so the scoring policy is dimension-agnostic.
 *
 * Feature taxonomy:
 *   confidence        — trustworthiness of underlying data
 *   data_breadth      — how many distinct signals are present
 *   comparative       — does it include comparative context?
 *   data_volume       — how many data points back this?
 *   freshness         — proxy via query elapsed time (lower latency = fresher)
 *   volatility        — magnitude of period-over-period movement (materialised change)
 *   breadth_of_impact — how many segments are affected vs expected (from inv. tree)
 *   anomaly_severity  — how far from baseline (z-score proxy from comparative ctx)
 *   downside_risk     — directional bias: downward movements rank higher (exec attention)
 *   comparative_depth — how many comparison types are present
 */
@Component
public class FeatureExtractor {

    private static final int    MAX_METRICS      = 20;
    private static final int    MAX_VOLUME       = 500;
    private static final long   MAX_ELAPSED_MS   = 10_000L;
    private static final double ANOMALY_THRESHOLD = 15.0; // 15% delta = anomaly

    public Map<String, Double> extract(EvidenceObject ev) {
        Map<String, Double> f = new LinkedHashMap<>();

        // ── base signals ─────────────────────────────────────────────────
        f.put("confidence", clamp(ev.confidence()));

        long nonNull = ev.metrics().values().stream().filter(v -> v != null).count();
        f.put("data_breadth", clamp((double) nonNull / MAX_METRICS));

        f.put("comparative", ev.comparisons().isEmpty() ? 0.0 : 1.0);

        long dataPoints = ev.signals().values().stream()
                .filter(v -> v != null && v.toString().matches("\\d+"))
                .mapToLong(v -> parseLong(v))
                .max().orElse(0L);
        f.put("data_volume", clamp((double) dataPoints / MAX_VOLUME));

        long maxElapsed = ev.signals().entrySet().stream()
                .filter(e -> e.getKey().endsWith("elapsed_ms") && e.getValue() != null)
                .mapToLong(e -> parseLong(e.getValue()))
                .max().orElse(0L);
        f.put("freshness", maxElapsed == 0 ? 0.5
                : clamp(1.0 - (double) maxElapsed / MAX_ELAPSED_MS));

        // ── comparative intelligence features ────────────────────────────
        List<ComparativeContext> ctxs = ev.comparativeContexts();

        // Volatility: max absolute deltaPercent across all comparisons
        double maxDeltaPct = ctxs.stream()
                .mapToDouble(c -> Math.abs(c.deltaPercent()))
                .max().orElse(0.0);
        f.put("volatility", clamp(maxDeltaPct / 100.0));

        // Anomaly severity: how many comparisons exceed the anomaly threshold
        long anomalyCount = ctxs.stream()
                .filter(c -> Math.abs(c.deltaPercent()) > ANOMALY_THRESHOLD)
                .count();
        f.put("anomaly_severity", clamp((double) anomalyCount / Math.max(1, ctxs.size())));

        // Downside risk: fraction of comparisons pointing DOWN (executives prioritise bad news)
        long downCount = ctxs.stream()
                .filter(c -> "down".equals(c.directionLabel())).count();
        f.put("downside_risk", ctxs.isEmpty() ? 0.0
                : clamp((double) downCount / ctxs.size()));

        // Comparative depth: how many distinct comparison types are present
        long distinctTypes = ctxs.stream()
                .map(ComparativeContext::comparisonType)
                .distinct().count();
        f.put("comparative_depth", clamp((double) distinctTypes / 5.0));

        // ── investigation tree features ──────────────────────────────────
        List<InvestigationNode> tree = ev.investigationTree();

        // Breadth of impact: how many root investigation signals were found
        f.put("breadth_of_impact", clamp((double) tree.size() / 5.0));

        // Max investigation impact score (normalised)
        double maxImpact = tree.stream()
                .mapToDouble(InvestigationNode::impactScore)
                .max().orElse(0.0);
        f.put("investigation_impact", clamp(maxImpact / 100.0));

        return f;
    }

    private double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }

    private long parseLong(Object val) {
        try { return Long.parseLong(val.toString()); }
        catch (Exception e) { return 0L; }
    }
}
