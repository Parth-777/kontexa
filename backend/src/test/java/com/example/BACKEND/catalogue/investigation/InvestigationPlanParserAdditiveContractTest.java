package com.example.BACKEND.catalogue.investigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestigationPlanParserAdditiveContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final InvestigationPlanParser parser =
            new InvestigationPlanParser();
    private final InvestigationObjectiveParser objectiveParser =
            new InvestigationObjectiveParser();

    @Test
    void oldPlanWithoutReasoningMetadataStillParses() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode phenomenon = root.putObject("phenomenon");
        phenomenon.put("subject", "tips");
        ObjectNode measure = phenomenon.putObject("measure");
        measure.put("column", "tip_amount");
        measure.put("aggregation", "AVG");
        measure.putArray("grain");
        root.putObject("scope").putArray("filters");
        ArrayNode requests = root.putArray("evidence_requests");
        ObjectNode req = requests.addObject();
        req.put("id", "focal");
        req.put("kind", "slice_metric");
        req.put("role", "FOCAL");
        ObjectNode reqMeasure = req.putObject("measure");
        reqMeasure.put("column", "tip_amount");
        reqMeasure.put("aggregation", "AVG");
        reqMeasure.putArray("grain");
        root.put("semantic_confidence", 0.8);

        InvestigationPlan plan = parser.parse(root, objectiveParser);
        assertNotNull(plan);
        assertTrue(plan.hypotheses().isEmpty());
        assertNull(plan.insufficiency());
        assertEquals(1, plan.evidenceRequests().size());
        assertNull(plan.evidenceRequests().getFirst().hypothesisId());
        assertNull(plan.evidenceRequests().getFirst().evidencePurpose());
    }

    @Test
    void additiveHypothesesPurposeAndInsufficiencyParse() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode phenomenon = root.putObject("phenomenon");
        phenomenon.put("subject", "desktop traffic higher than mobile");
        ObjectNode measure = phenomenon.putObject("measure");
        measure.put("column", "totals.visits");
        measure.put("aggregation", "SUM");
        measure.putArray("grain");
        root.putObject("scope").putArray("filters");

        ArrayNode hypotheses = root.putArray("hypotheses");
        ObjectNode h1 = hypotheses.addObject();
        h1.put("id", "H1");
        h1.put("claim", "Desktop traffic is higher because organic traffic favors desktop.");
        h1.put("status", "ALTERNATIVE");
        ObjectNode h2 = hypotheses.addObject();
        h2.put("id", "H2");
        h2.put("claim", "Desktop traffic is higher because direct traffic dominates.");
        h2.put("status", "PRIMARY");
        ObjectNode h3 = hypotheses.addObject();
        h3.put("id", "H3");
        h3.put("claim", "Desktop traffic is higher because desktop has higher engagement.");
        h3.put("status", "REJECTED_CANDIDATE");

        ArrayNode requests = root.putArray("evidence_requests");
        ObjectNode comparison = requests.addObject();
        comparison.put("id", "ev_contrast");
        comparison.put("kind", "slice_metric");
        comparison.put("role", "FOCAL");
        comparison.putNull("hypothesis_id");
        comparison.put("evidence_purpose", "COMPARISON");
        ObjectNode cm = comparison.putObject("measure");
        cm.put("column", "totals.visits");
        cm.put("aggregation", "SUM");
        cm.putArray("grain");

        ObjectNode primary = requests.addObject();
        primary.put("id", "ev_h2");
        primary.put("kind", "metric_by_dimension");
        primary.putNull("role");
        primary.put("hypothesis_id", "H2");
        primary.put("evidence_purpose", "PRIMARY");
        ObjectNode pm = primary.putObject("measure");
        pm.put("column", "totals.visits");
        pm.put("aggregation", "SUM");
        pm.putArray("grain");
        primary.put("dimension", "trafficSource.source");

        ObjectNode insuff = root.putObject("insufficiency");
        insuff.put("answerable", true);
        ArrayNode unknowns = insuff.putArray("unknowns");
        unknowns.add("marketing spend unavailable");
        unknowns.add("campaign attribution unavailable");
        insuff.putArray("blocked_reasons");
        root.put("semantic_confidence", 0.72);

        InvestigationPlan plan = parser.parse(root, objectiveParser);
        assertNotNull(plan);
        assertEquals(3, plan.hypotheses().size());
        assertEquals("H2", plan.hypotheses().get(1).id());
        assertEquals(InvestigationPlan.Hypothesis.PRIMARY, plan.hypotheses().get(1).status());
        assertEquals(InvestigationPlan.Hypothesis.REJECTED_CANDIDATE, plan.hypotheses().get(2).status());

        assertEquals(2, plan.evidenceRequests().size());
        assertEquals("COMPARISON", plan.evidenceRequests().getFirst().evidencePurpose());
        assertNull(plan.evidenceRequests().getFirst().hypothesisId());
        assertEquals("H2", plan.evidenceRequests().get(1).hypothesisId());
        assertEquals("PRIMARY", plan.evidenceRequests().get(1).evidencePurpose());

        assertNotNull(plan.insufficiency());
        assertTrue(plan.insufficiency().answerable());
        assertEquals(2, plan.insufficiency().unknowns().size());
        assertTrue(plan.insufficiency().blockedReasons().isEmpty());
    }

    @Test
    void unanswerableInsufficiencyParses() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode phenomenon = root.putObject("phenomenon");
        phenomenon.put("subject", "bounce rate");
        phenomenon.putNull("measure");
        root.putObject("scope").putArray("filters");
        root.putArray("hypotheses");
        root.putArray("evidence_requests");
        ObjectNode insuff = root.putObject("insufficiency");
        insuff.put("answerable", false);
        insuff.putArray("unknowns").add("bounce metric unavailable");
        insuff.putArray("blocked_reasons").add("catalogue missing conversion funnel");
        root.put("semantic_confidence", 0.4);

        InvestigationPlan plan = parser.parse(root, objectiveParser);
        assertFalse(plan.insufficiency().answerable());
        assertEquals(1, plan.insufficiency().blockedReasons().size());
    }
}
