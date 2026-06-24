package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.planning.SemanticShadowComparison;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SemanticShadowResponseMapper {

    public Map<String, Object> toMap(SemanticShadowComparison comparison) {
        if (comparison == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planner_mode", comparison.plannerMode());
        m.put("served_path", comparison.servedPath());
        m.put("question", comparison.question());
        m.put("gpt_confidence", comparison.gptConfidence());
        m.put("gpt_validation_valid", comparison.gptValidationValid());
        m.put("gpt_validation_issues", comparison.gptValidationIssues());
        m.put("legacy_analysis_plan", comparison.legacyAnalysisPlan());
        m.put("gpt_structured_plan", comparison.gptStructuredPlan());
        m.put("gpt_analysis_plan", comparison.gptAnalysisPlan());
        m.put("legacy_sql", comparison.legacySql());
        m.put("gpt_sql", comparison.gptSql());
        m.put("legacy_execution", comparison.legacyExecution());
        m.put("gpt_execution", comparison.gptExecution());
        m.put("divergence", comparison.divergence());
        if (comparison.error() != null && !comparison.error().isBlank()) {
            m.put("error", comparison.error());
        }
        return m;
    }

    public Map<String, Object> plannerStatus(SemanticPlanningProperties properties) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", properties.getMode().name());
        m.put("shadow_active", properties.isShadow());
        m.put("gpt_active", properties.isGpt());
        m.put("served_path", properties.isGpt() ? "gpt" : "legacy");
        m.put("shadow_execute_gpt_sql", properties.isShadowExecuteGptSql());
        m.put("min_confidence", properties.getMinConfidence());
        return m;
    }
}
