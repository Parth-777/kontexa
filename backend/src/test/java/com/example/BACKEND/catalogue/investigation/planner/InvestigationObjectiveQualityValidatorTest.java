package com.example.BACKEND.catalogue.investigation.planner;

import com.example.BACKEND.catalogue.investigation.InvestigationObjective;
import com.example.BACKEND.catalogue.investigation.InvestigationObjectiveParser;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestigationObjectiveQualityValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final InvestigationObjectiveParser parser = new InvestigationObjectiveParser();
    private final InvestigationObjectiveQualityValidator validator =
            new InvestigationObjectiveQualityValidator();

    @Test
    void acceptsWellFormedPartitionObjective() throws Exception {
        var objective = parser.parse(MAPPER.readTree("""
                {
                  "measure": { "column": "tip_amount", "aggregation": "AVG", "grain": [] },
                  "scope": { "filters": [] },
                  "comparison": {
                    "focal": { "constraints": [{ "filter": { "column": "payment_type", "operator": "=", "value": "1" } }] },
                    "baseline": { "constraints": [{ "filter": { "column": "payment_type", "operator": "=", "value": "2" } }] }
                  },
                  "decomposition": {
                    "dimensions": ["trip_distance", "region"],
                    "exclude": ["tip_amount", "payment_type"]
                  },
                  "salience": "FOCAL_HIGHER",
                  "semantic_confidence": 0.9
                }
                """));
        var plan = planWithMode(StructuredSemanticPlan.MODE_INVESTIGATION);
        var result = validator.validate("Why do type 1 tips exceed type 2?", plan, objective,
                InvestigationPlannerFixtures.catalogue());
        assertTrue(result.pass());
    }

    @Test
    void rejectsMissingInvestigationObjective() {
        var plan = planWithMode(StructuredSemanticPlan.MODE_INVESTIGATION);
        var result = validator.validate("Why did revenue drop?", plan, null,
                InvestigationPlannerFixtures.catalogue());
        assertFalse(result.pass());
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.code().equals("MISSING_INVESTIGATION_OBJECTIVE")));
    }

    @Test
    void rejectsUnboundColumnAndMissingExclude() throws Exception {
        var objective = parser.parse(MAPPER.readTree("""
                {
                  "measure": { "column": "revenue_xyz", "aggregation": "SUM", "grain": [] },
                  "scope": { "filters": [] },
                  "comparison": {
                    "focal": { "constraints": [{ "filter": { "column": "payment_type", "operator": "=", "value": "1" } }] },
                    "baseline": { "constraints": [{ "population": true }] }
                  },
                  "decomposition": { "dimensions": ["region"], "exclude": [] },
                  "salience": "DIFFERENCE",
                  "semantic_confidence": 0.5
                }
                """));
        var plan = planWithMode(StructuredSemanticPlan.MODE_INVESTIGATION);
        var result = validator.validate("bad bindings", plan, objective,
                InvestigationPlannerFixtures.catalogue());
        assertFalse(result.pass());
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("UNBOUND_COLUMN")));
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("MISSING_EXCLUDE_LIST")));
        assertTrue(result.ambiguous());
    }

    private static StructuredSemanticPlan planWithMode(String mode) {
        return new StructuredSemanticPlan(
                "DISTRIBUTION", "total_amount", null, List.of(), List.of(),
                new StructuredSemanticPlan.SemanticAggregations("SUM", null),
                null, null, null, null, 0.9, "test", List.of(),
                mode, null, null);
    }
}
