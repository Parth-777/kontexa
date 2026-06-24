package com.example.BACKEND.catalogue.decision.execution.materialization;

import com.example.BACKEND.catalogue.decision.execution.framework.SchemaProfiler;
import com.example.BACKEND.catalogue.decision.grounding.PresentationLabelResolver;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import com.example.BACKEND.catalogue.decision.presentation.MetricBucketingEngine;
import com.example.BACKEND.catalogue.decision.reasoning.QuestionDrivenReasoningPlan;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticalResultMaterializationTest {

    private final SchemaProfiler schemaProfiler = new SchemaProfiler();
    private final AnalyticalQueryMaterializer materializer = new AnalyticalQueryMaterializer(
            new DerivedDimensionMaterializer(),
            new NumericDimensionBucketer(new MetricBucketingEngine()),
            new MaterializationPlanBuilder(new PresentationLabelResolver()),
            new GroupByExecutor(),
            new PresentationLabelResolver());

    @Test
    void correlationRow_isMaterialized_notDiscarded() {
        List<Map<String, Object>> rows = List.of(correlationRow(-0.0134, 10000));

        var shape = AnalyticalWarehouseResultDetector.detectCorrelation(rows);
        assertTrue(shape.isPresent());
        assertEquals(-0.0134, shape.get().coefficient(), 0.0001);
        assertEquals(10000L, shape.get().sampleSize());

        var profile = schemaProfiler.profile(rows);
        var result = materializer.materialize(rows, profile, relationshipPlan());

        assertEquals(AnalyticalResultType.CORRELATION_RESULT, result.resultType());
        assertTrue(result.hasContent(), "Single-row CORR result must not be discarded");
        assertNotNull(result.correlation());
        assertEquals(-0.0134, result.correlation().correlationCoefficient(), 0.0001);
        assertEquals(10000L, result.correlation().sampleSize());
        assertFalse(result.correlation().interpretation().isBlank());
        assertEquals(3, result.findings().size());
    }

    @Test
    void contributionScalarRow_isMaterialized() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("segment_label", "Airport");
        row.put("total_amount", 125_000.0);
        row.put("share_pct", 18.4);
        List<Map<String, Object>> rows = List.of(row);

        var shape = AnalyticalWarehouseResultDetector.detectScalar(rows);
        assertTrue(shape.isPresent());
        assertEquals("total_amount", shape.get().metricColumn());
        assertEquals(18.4, shape.get().sharePct(), 0.01);

        var profile = schemaProfiler.profile(rows);
        var result = materializer.materialize(rows, profile, null);

        assertEquals(AnalyticalResultType.SCALAR_RESULT, result.resultType());
        assertTrue(result.hasContent());
        assertEquals(18.4, result.scalar().sharePct(), 0.01);
        assertFalse(result.findings().isEmpty());
        assertTrue(result.findings().getFirst().findingText().contains("18.4"));
    }

    @Test
    void scalarRow_isMaterialized() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("total_revenue", 1_250_000.0);
        List<Map<String, Object>> rows = List.of(row);

        var shape = AnalyticalWarehouseResultDetector.detectScalar(rows);
        assertTrue(shape.isPresent());

        var profile = schemaProfiler.profile(rows);
        var result = materializer.materialize(rows, profile, null);

        assertEquals(AnalyticalResultType.SCALAR_RESULT, result.resultType());
        assertTrue(result.hasContent());
        assertEquals(1_250_000.0, result.scalar().value(), 1.0);
    }

    @Test
    void groupedRows_stillMaterialize() {
        List<Map<String, Object>> rows = List.of(
                entityRow("North Sea", 54000.0),
                entityRow("Gulf Field", 48000.0),
                entityRow("Permian Basin", 65000.0));

        var profile = schemaProfiler.profile(rows);
        var result = materializer.materialize(rows, profile, null);

        assertEquals(AnalyticalResultType.GROUPED_RESULT, result.resultType());
        assertTrue(result.hasContent());
        assertEquals(3, result.primaryGrouping().groupCount());
    }

    private static Map<String, Object> correlationRow(double coefficient, long count) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("correlation_coefficient", coefficient);
        row.put("row_count", count);
        return row;
    }

    private static Map<String, Object> entityRow(String entity, double value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entity", entity);
        row.put("revenue", value);
        return row;
    }

    private static InvestigationPlan relationshipPlan() {
        MetricResolution resolution = new MetricResolution(
                "profit_margin", "Profit Margin",
                null, null,
                "downtime_hours", "Downtime Hours",
                null, null,
                null,
                0.9, false, "",
                List.of(), null);
        QuestionDrivenReasoningPlan qdPlan = new QuestionDrivenReasoningPlan(
                "How does downtime affect profitability?",
                AnalyticalIntentType.RELATIONSHIP,
                List.of(),
                List.of(),
                null,
                QuestionSemantics.unresolved("How does downtime affect profitability?"),
                resolution,
                List.of());
        return new InvestigationPlan(
                "test-plan",
                AnalyticalIntentType.RELATIONSHIP,
                null,
                List.of(),
                List.of(),
                null,
                List.of(),
                "relationship test",
                null,
                null,
                qdPlan);
    }
}
