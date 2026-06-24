package com.example.BACKEND.experiment.phase1;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;

import java.util.List;

/**
 * Primary planner output plus optional alternates when confidence is low.
 */
public record Phase1PlannerOutput(
        Phase1PlannerCandidate primary,
        List<Phase1PlannerCandidate> alternates,
        List<QuerySpec> querySpecs
) {
    public String metric() { return primary.metric(); }
    public String aggregation() { return primary.aggregation(); }
    public List<String> dimensions() { return primary.dimensions(); }
    public List<Phase1FilterSpec> filters() { return primary.filters(); }
    public Phase1OrderingSpec ordering() { return primary.ordering(); }
    public Integer limit() { return primary.limit(); }
    public double confidence() { return primary.confidence(); }
    public String reasoning() { return primary.reasoning(); }
    public com.example.BACKEND.catalogue.decision.planning.AnalysisIntent intent() {
        return primary.intent();
    }
}
