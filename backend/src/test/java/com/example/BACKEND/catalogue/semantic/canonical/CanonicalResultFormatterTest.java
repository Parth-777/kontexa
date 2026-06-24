package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.decision.presentation.executive.SemanticMetricFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalResultFormatterTest {

    private CanonicalResultFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new CanonicalResultFormatter(new SemanticMetricFormatter());
    }

    @Test
    void formatsScalarRowWithoutChangingValue() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM"),
                null, List.of(), null, null, null, null,
                new CanonicalQueryModel.PlannerMetadata("SCALAR", 0.9, "", List.of(), null, null));

        FormattedExecutiveTable table = formatter.format(
                model, List.of(Map.of("total_revenue", 79462378.9482)));

        assertEquals(1, table.rowCount());
        assertTrue(table.formattedRows().getFirst().get("total_revenue").contains("M"));
    }

    @Test
    void addsRankColumnWhenOrderingExists() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("operation_cost", "SUM"),
                new CanonicalQueryModel.PartitionSpec("company_name", null),
                List.of(),
                null,
                null,
                new CanonicalQueryModel.OrderSpec("operation_cost", "DESC"),
                null,
                new CanonicalQueryModel.PlannerMetadata("RANKING", 0.9, "", List.of("company_name"), null, null));

        FormattedExecutiveTable table = formatter.format(model, List.of(
                row("PetroNova Energy", 1.5627360503500023E10),
                row("Atlas Petroleum", 1.5557127095410007E10)));

        assertEquals("1", table.formattedRows().get(0).get("rank"));
        assertEquals("2", table.formattedRows().get(1).get("rank"));
        String cost = table.formattedRows().get(0).get("operation_cost");
        assertTrue(cost != null && cost.contains("B"), "operation_cost formatted as: " + cost);
        assertFalse(table.formattedRows().get(0).containsKey("operation_cost_formatted"));
    }

    @Test
    void humanizesColumnLabels() {
        FormattedExecutiveTable table = formatter.format(
                null,
                List.of(Map.of("company_name", "Acme", "operation_cost", 1000)));

        assertEquals("company name", table.columns().stream()
                .filter(c -> "company_name".equals(c.key()))
                .findFirst().orElseThrow().label());
    }

    private static Map<String, Object> row(String company, double cost) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("company_name", company);
        row.put("operation_cost", cost);
        return row;
    }
}
