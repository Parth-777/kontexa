package com.example.BACKEND.catalogue.decision.runtime;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Resolves the analytical objective from a free-text question.
 *
 * Maps user language to specific playbook objective keys.
 * Each key is matched to a {@link com.example.BACKEND.catalogue.decision.playbooks.Playbook}
 * by {@link com.example.BACKEND.catalogue.decision.playbooks.PlaybookRouter}.
 *
 * Phase 1: keyword-based heuristic.
 * Later phases: LLM classifier with few-shot examples.
 */
@Component
public class IntentResolver {

    public IntentResolution resolve(DecisionExecutionContext ctx) {
        String q = ctx.question().toLowerCase(Locale.ROOT);
        String objectiveKey;
        double confidence;

        // ── Anomaly / Investigation ────────────────────────────────────────
        if (containsAny(q, "anomaly", "spike", "outlier", "unusual", "unexpected",
                           "strange", "weird", "investigate", "why did", "what happened",
                           "sudden", "drop", "crash")) {
            objectiveKey = "ANOMALY_INVESTIGATION";
            confidence   = 0.85;

        // ── Revenue drivers ───────────────────────────────────────────────
        } else if (containsAny(q, "revenue", "sales", "earning", "income", "driver",
                                  "what is driving", "hurting", "causing", "behind",
                                  "contributing", "contribution")) {
            objectiveKey = "REVENUE_DRIVER_ANALYSIS";
            confidence   = 0.83;

        // ── Growth / Momentum ─────────────────────────────────────────────
        } else if (containsAny(q, "growth", "momentum", "accelerat", "fastest growing",
                                  "expanding", "emerging", "opportunity", "opportunities")) {
            objectiveKey = "GROWTH_MOMENTUM";
            confidence   = 0.82;

        // ── Operational / Efficiency ──────────────────────────────────────
        } else if (containsAny(q, "efficien", "bottleneck", "throughput", "utiliz",
                                  "operational", "cost", "latency", "idle", "slow",
                                  "underperform", "waste", "inefficien")) {
            objectiveKey = "OPERATIONAL_EFFICIENCY";
            confidence   = 0.80;

        // ── Strategic ranking ─────────────────────────────────────────────
        } else if (containsAny(q, "top", "rank", "best", "worst", "leading", "highest",
                                  "lowest", "most valuable", "strategic", "portfolio",
                                  "which", "who")) {
            objectiveKey = "STRATEGIC_VALUE_RANKING";
            confidence   = 0.82;

        // ── Trend / Time-series ───────────────────────────────────────────
        } else if (containsAny(q, "trend", "over time", "month", "week", "period",
                                  "decline", "trajectory", "history", "compare period")) {
            objectiveKey = "TREND_ANALYSIS";
            confidence   = 0.78;

        // ── Comparative ───────────────────────────────────────────────────
        } else if (containsAny(q, "compare", "versus", " vs ", "difference", "gap",
                                  "against", "relative to")) {
            objectiveKey = "COMPARATIVE_ANALYSIS";
            confidence   = 0.76;

        // ── Distribution / Breakdown ──────────────────────────────────────
        } else if (containsAny(q, "distribution", "breakdown", "segment", "split",
                                  " by ", "group by", "proportion")) {
            objectiveKey = "DISTRIBUTION_ANALYSIS";
            confidence   = 0.73;

        } else {
            objectiveKey = "GENERAL_ANALYSIS";
            confidence   = 0.60;
        }

        return new IntentResolution(ctx.runId(), ctx.tenantId(), ctx.question(), objectiveKey, confidence);
    }

    private boolean containsAny(String text, String... tokens) {
        for (String t : tokens) {
            if (text.contains(t)) return true;
        }
        return false;
    }
}
