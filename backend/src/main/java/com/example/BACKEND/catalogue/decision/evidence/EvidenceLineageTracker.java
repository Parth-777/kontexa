package com.example.BACKEND.catalogue.decision.evidence;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Builds lineage reference strings for each piece of evidence.
 *
 * Lineage refs are human-readable identifiers that trace an evidence object
 * back to the exact query keys and run ID that produced it.
 * Format: "run:{runId}#query:{queryKey}"
 */
@Component
public class EvidenceLineageTracker {

    public List<String> buildRefs(List<QueryResult> queryResults, UUID runId) {
        return queryResults.stream()
                .map(qr -> "run:" + runId + "#query:" + qr.key())
                .toList();
    }
}
