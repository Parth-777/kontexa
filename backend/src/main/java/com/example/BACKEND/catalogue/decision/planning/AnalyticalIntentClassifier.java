package com.example.BACKEND.catalogue.decision.planning;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.IntentResolution;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Classifies user questions into analytical intents that drive the entire pipeline.
 *
 * Primary intents: contribution, trend, comparison, anomaly, ranking, correlation,
 * distribution, retention, efficiency, composition.
 *
 * Deterministic — no LLM. Priority: objectiveKey → question text → fallback.
 */
@Component
public class AnalyticalIntentClassifier {

    public AnalyticalIntentType classify(IntentResolution intent) {
        String key      = intent.objectiveKey().toUpperCase(Locale.ROOT);
        String question = intent.question().toLowerCase(Locale.ROOT);

        // Playbook-level objective keys
        if (key.contains("ANOMALY"))            return AnalyticalIntentType.ANOMALY_DETECTION;
        if (key.contains("REVENUE_DRIVER"))     return AnalyticalIntentType.CONTRIBUTION;
        if (key.contains("GROWTH_MOMENTUM"))    return AnalyticalIntentType.TREND_ANALYSIS;
        if (key.contains("OPERATIONAL_EFF"))    return AnalyticalIntentType.EFFICIENCY;
        if (key.contains("STRATEGIC_VALUE"))    return AnalyticalIntentType.STRATEGIC_PRIORITIZATION;
        if (key.contains("RANKING"))            return AnalyticalIntentType.RANKING;

        // Question text — most specific patterns first
        if (matchesAny(question, "affect", "impact", "influence", "relationship between",
                "relate to", "related to", "associated with", "correlat", "co-move", "co move",
                "depends on", "what drives", "what drive"))
            return AnalyticalIntentType.RELATIONSHIP;

        if (matchesAny(question, "correlat", "relationship between", "co-move", "co move",
                "associated with", "linked to", "depends on"))
            return AnalyticalIntentType.CORRELATION;

        if (matchesAny(question, "retention", "churn", "repeat customer", "repeat rider",
                "come back", "cohort retention", "staying"))
            return AnalyticalIntentType.RETENTION;

        if (matchesAny(question, "efficien", "per mile", "per trip", "yield", "productivity",
                "revenue per", "output per", "unit economics"))
            return AnalyticalIntentType.EFFICIENCY;

        if (matchesAny(question, "composition", "mix", "portfolio", "make up", "made up of",
                "breakdown of total", "share of portfolio"))
            return AnalyticalIntentType.COMPOSITION;

        if (matchesAny(question, "why", "what caused", "root cause", "reason for",
                "behind", "explain the", "what happened", "went wrong", "drove"))
            return AnalyticalIntentType.ROOT_CAUSE_INVESTIGATION;

        if (matchesAny(question, "anomal", "unusual", "unexpected", "spike",
                "outlier", "abnormal", "sudden", "strange"))
            return AnalyticalIntentType.ANOMALY_DETECTION;

        if (matchesAny(question, "most valuable", "highest value", "priorit",
                "strategic", "best investment", "worth", "best zone", "best segment",
                "which should", "which is best"))
            return AnalyticalIntentType.STRATEGIC_PRIORITIZATION;

        if (matchesAny(question, "forecast", "predict", "next quarter", "next month",
                "future", "projection", "expected to"))
            return AnalyticalIntentType.FORECASTING;

        if (matchesAny(question, "trend", "over time", "growing", "declining over",
                "historical", "quarter by quarter", "month by month",
                "year over year", "momentum", "trajectory"))
            return AnalyticalIntentType.TREND_ANALYSIS;

        if (matchesAny(question, "rank", "top", "bottom", "highest", "lowest",
                "best performing", "worst performing", "sorted by"))
            return AnalyticalIntentType.RANKING;

        if (matchesAny(question, "compare", "versus", " vs ", "difference between",
                "better than", "worse than", "relative to"))
            return AnalyticalIntentType.COMPARISON;

        if (matchesAny(question, "contribution", "contribute", "how much does", "how does",
                "share of", "percentage of", "proportion", "part of total", "fraction",
                "accounts for", "drives"))
            return AnalyticalIntentType.CONTRIBUTION;

        if (matchesAny(question, "distribution", "spread", "histogram", "bell curve",
                "skew", "variance", "dispersion"))
            return AnalyticalIntentType.DISTRIBUTION;

        if (matchesAny(question, "breakdown", "split", "segment", "by region",
                "by category", "by type", "per zone", "grouped by"))
            return AnalyticalIntentType.DISTRIBUTION;

        return AnalyticalIntentType.GENERAL_ANALYSIS;
    }

    private boolean matchesAny(String text, String... patterns) {
        for (String p : patterns) {
            if (text.contains(p)) return true;
        }
        return false;
    }
}
