package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.execution.trace.ExecutionTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalRuntimeFinisherTest {

  @Test
  void countCanonicalRowsIgnoresMetricPackResults() {
    List<QuerySpec> specs = List.of(new QuerySpec("canonical__sql", "SELECT 1", Map.of()));
    List<QueryResult> results = List.of(
        new QueryResult("canonical__sql", List.of(), 1),
        new QueryResult("metric_pack__total", List.of(Map.of("x", 1)), 1));

    assertEquals(0, CanonicalRuntimeFinisher.countCanonicalRows(specs, results));
  }

  @Test
  void noMatchingDataReturnsFixedMessage() {
    UUID runId = UUID.randomUUID();
    ExecutionTrace trace = ExecutionTrace.start().complete();

    DecisionRunResult result = CanonicalRuntimeFinisher.noMatchingData(runId, trace);

    assertEquals(CanonicalRuntimeFinisher.NO_MATCHING_DATA, result.insight().narrative());
    assertEquals(CanonicalRuntimeFinisher.NO_MATCHING_DATA, result.analytical().executiveSummary());
    assertTrue(result.analytical().findings().isEmpty());
    assertTrue(result.analytical().metrics().isEmpty());
  }
}
