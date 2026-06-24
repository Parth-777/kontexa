package com.example.BACKEND.catalogue.decision.synthesis.answer;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerSynthesisInputBuilderTest {

  @Test
  void extractCanonicalRowsReturnsOnlyMatchingKeys() {
    List<QuerySpec> specs = List.of(new QuerySpec("canonical__sql", "SELECT 1", Map.of()));
    List<QueryResult> results = List.of(
        new QueryResult("canonical__sql", List.of(Map.of("revenue", 100)), 1),
        new QueryResult("metric_pack__total", List.of(Map.of("revenue", 999)), 1));

    List<Map<String, Object>> rows = AnswerSynthesisInputBuilder.extractCanonicalRows(specs, results);

    assertEquals(1, rows.size());
    assertEquals(100, rows.getFirst().get("revenue"));
  }

  @Test
  void extractCanonicalRowsReturnsEmptyWhenCanonicalHasNoRows() {
    List<QuerySpec> specs = List.of(new QuerySpec("canonical__sql", "SELECT 1", Map.of()));
    List<QueryResult> results = List.of(
        new QueryResult("canonical__sql", List.of(), 1),
        new QueryResult("metric_pack__total", List.of(Map.of("revenue", 999)), 1));

    List<Map<String, Object>> rows = AnswerSynthesisInputBuilder.extractCanonicalRows(specs, results);

    assertTrue(rows.isEmpty());
  }

  @Test
  void canonicalScalarIgnoresLegacyDimensionDecoy() {
    AnswerSynthesisProperties props = new AnswerSynthesisProperties();
    AnswerSynthesisInputBuilder builder = new AnswerSynthesisInputBuilder(props);

    var canonical = new com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel(
        new com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel.MeasureSpec(
            "total_revenue", "SUM"),
        null,
        List.of(),
        null,
        null,
        null,
        null,
        new com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel.PlannerMetadata(
            "SCALAR", 0.9, "", List.of(), null, null));

    List<QuerySpec> specs = List.of(new QuerySpec("canonical__sql", "SELECT 1", Map.of()));
    ComputationResultSet results = new ComputationResultSet(
        UUID.randomUUID(),
        List.of(new QueryResult("canonical__sql", List.of(Map.of("total_revenue", 100)), 1)),
        Map.of());

    MetricResolution legacy = new MetricResolution(
        "LEGACY_DECOY_METRIC", "Legacy", null, null,
        "LEGACY_DECOY_DIMENSION", "Legacy Region", "LEGACY_DECOY_DIMENSION", 0.99, false, null);

    AnswerSynthesisInput input = builder.build(
        "What is total revenue?",
        specs,
        results,
        legacy,
        null,
        0.9,
        null,
        UUID.randomUUID(),
        canonical);

    assertEquals("total_revenue", input.metric().column());
    assertEquals("", input.dimension().column());
  }
}
