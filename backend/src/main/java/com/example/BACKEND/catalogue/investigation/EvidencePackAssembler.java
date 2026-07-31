package com.example.BACKEND.catalogue.investigation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles an evidence-first {@link EvidencePack} from request-level results.
 */
@Component
public class EvidencePackAssembler {

    public EvidencePack assemble(
            String question,
            InvestigationPlan plan,
            String tableRef,
            List<EvidencePack.RequestResult> requestResults
    ) {
        List<EvidencePack.RequestResult> results =
                requestResults == null ? List.of() : List.copyOf(requestResults);

        int planned = results.size();
        int succeeded = 0;
        int failed = 0;
        for (EvidencePack.RequestResult rr : results) {
            if (rr != null && rr.ok()) {
                succeeded++;
            } else {
                failed++;
            }
        }

        EvidencePack.Coverage coverage = new EvidencePack.Coverage(planned, succeeded, failed);
        EvidencePack.Confidence confidence = scoreConfidence(coverage, results);

        InvestigationPlan.Phenomenon phenomenon = plan != null ? plan.phenomenon() : null;
        List<InvestigationPlan.Hypothesis> hypotheses = plan != null && plan.hypotheses() != null
                ? plan.hypotheses()
                : List.of();
        InvestigationPlan.Insufficiency insufficiency = plan != null ? plan.insufficiency() : null;

        return new EvidencePack(
                question,
                phenomenon,
                hypotheses,
                insufficiency,
                results,
                coverage,
                confidence,
                tableRef);
    }

    private EvidencePack.Confidence scoreConfidence(
            EvidencePack.Coverage coverage,
            List<EvidencePack.RequestResult> results
    ) {
        List<String> reasons = new ArrayList<>();
        if (coverage.requestsPlanned() == 0) {
            return new EvidencePack.Confidence(
                    EvidencePack.Confidence.LOW,
                    List.of("no evidence requests were planned"));
        }

        double successRate = (double) coverage.requestsSucceeded() / coverage.requestsPlanned();
        String level;
        if (coverage.requestsFailed() == 0 && coverage.requestsSucceeded() > 0) {
            level = EvidencePack.Confidence.HIGH;
            reasons.add("all " + coverage.requestsSucceeded() + " evidence requests succeeded");
        } else if (successRate >= 0.5) {
            level = EvidencePack.Confidence.MODERATE;
            reasons.add(coverage.requestsSucceeded() + "/" + coverage.requestsPlanned()
                    + " evidence requests succeeded");
        } else {
            level = EvidencePack.Confidence.LOW;
            reasons.add("only " + coverage.requestsSucceeded() + "/" + coverage.requestsPlanned()
                    + " evidence requests succeeded");
        }

        for (EvidencePack.RequestResult rr : results) {
            if (rr != null && !rr.ok() && rr.error() != null) {
                reasons.add(rr.requestId() + ": " + rr.error());
            }
        }
        return new EvidencePack.Confidence(level, List.copyOf(reasons));
    }
}
