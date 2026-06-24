package com.example.BACKEND.catalogue.decision.planning;

import java.util.List;
import java.util.Map;

/**
 * Per-request legacy vs GPT planner comparison (shadow mode only).
 * Legacy path is always served to the user; GPT path is observability-only.
 */
public record SemanticShadowComparison(
        String plannerMode,
        String servedPath,
        String question,
        double gptConfidence,
        boolean gptValidationValid,
        List<String> gptValidationIssues,
        Map<String, Object> legacyAnalysisPlan,
        Map<String, Object> gptStructuredPlan,
        Map<String, Object> gptAnalysisPlan,
        List<Map<String, Object>> legacySql,
        List<Map<String, Object>> gptSql,
        List<Map<String, Object>> legacyExecution,
        List<Map<String, Object>> gptExecution,
        Map<String, Object> divergence,
        String error
) {}
