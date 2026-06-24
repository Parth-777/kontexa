package com.example.BACKEND.catalogue.decision.clarification;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.InsightOutput;
import com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse;
import com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse.InsightBlock;
import com.example.BACKEND.catalogue.decision.presentation.EvidencePanel;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutiveInsightCard;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationLayer;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Recovery-oriented responses when viability fails or evidence is insufficient.
 */
@Component
public class RecoveryResponseBuilder {

    private final ExecutivePresentationLayer executiveLayer;

    public RecoveryResponseBuilder(ExecutivePresentationLayer executiveLayer) {
        this.executiveLayer = executiveLayer;
    }

    public DecisionRunResult buildRecovery(
            ResolvedAnalyticalQuestion resolved,
            String reason,
            RecoveryKind kind
    ) {
        AnalyticalAssumption a = resolved.assumption();
        String summary = buildSummary(a, reason, kind);

        InsightOutput insight = new InsightOutput(
                "REC-" + UUID.randomUUID().toString().substring(0, 8),
                recoveryTitle(kind),
                summary,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ""
        );

        EvidencePanel panel = new EvidencePanel(
                a.primaryMetricLabel(),
                a.grouping(),
                a.aggregation().name(),
                0,
                String.format(Locale.ROOT, "Trust=%.0f%% — %s",
                        a.resolutionConfidence() * 100, kind.name().toLowerCase(Locale.ROOT))
        );

        ExecutiveInsightCard executiveCard = executiveLayer.present(
                null, null, null, null, a.resolutionConfidence(),
                true, reason, resolved, null);

        AnalyticalResponse analytical = new AnalyticalResponse(
                summary,
                List.of(),
                List.of(),
                null,
                new InsightBlock(recoveryTitle(kind), summary),
                a.resolutionConfidence(),
                panel,
                a,
                resolved.alternatives(),
                true,
                resolved.availableMetrics(),
                resolved.suggestedReformulation(),
                reason,
                executiveCard,
                false,
                "",
                com.example.BACKEND.catalogue.decision.exploration.PlannerConfidenceTier.LOW,
                com.example.BACKEND.catalogue.decision.exploration.AnalyticalExecutionMode.EXPLORATORY_HEURISTIC,
                null,
                com.example.BACKEND.catalogue.decision.presentation.ResponseMode.KPI,
                com.example.BACKEND.catalogue.decision.presentation.TableSpec.empty(),
                null,
                null
        );

        return new DecisionRunResult(insight, analytical);
    }

    private String buildSummary(AnalyticalAssumption a, String reason, RecoveryKind kind) {
        StringBuilder sb = new StringBuilder();
        if (a.ambiguityNote() != null && !a.ambiguityNote().isBlank()) {
            sb.append(a.ambiguityNote()).append(" ");
        }
        sb.append("The available data does not support a reliable pattern for this question yet.");
        return sb.toString().trim();
    }

    private String recoveryTitle(RecoveryKind kind) {
        return switch (kind) {
            case VIABILITY_FAILED -> "Analysis not viable";
            case INSUFFICIENT_EVIDENCE -> "Insufficient evidence";
        };
    }

    public enum RecoveryKind {
        VIABILITY_FAILED,
        INSUFFICIENT_EVIDENCE
    }
}
