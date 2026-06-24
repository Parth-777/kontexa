package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
import com.example.BACKEND.catalogue.agent.CollectedData;
import com.example.BACKEND.catalogue.entity.SignalEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LensInsightCoordinator {

    private final GrowthLensAgent growthLensAgent;
    private final RiskLensAgent riskLensAgent;
    private final EfficiencyLensAgent efficiencyLensAgent;
    private final CustomerLensAgent customerLensAgent;
    private final GeneralDiscoveryLensAgent generalDiscoveryLensAgent;
    private final RevenueModelLensAgent revenueModelLensAgent;
    private final InsightCandidateExpander candidateExpander;

    public LensInsightCoordinator(
            GrowthLensAgent growthLensAgent,
            RiskLensAgent riskLensAgent,
            EfficiencyLensAgent efficiencyLensAgent,
            CustomerLensAgent customerLensAgent,
            GeneralDiscoveryLensAgent generalDiscoveryLensAgent,
            RevenueModelLensAgent revenueModelLensAgent,
            InsightCandidateExpander candidateExpander
    ) {
        this.growthLensAgent = growthLensAgent;
        this.riskLensAgent = riskLensAgent;
        this.efficiencyLensAgent = efficiencyLensAgent;
        this.customerLensAgent = customerLensAgent;
        this.generalDiscoveryLensAgent = generalDiscoveryLensAgent;
        this.revenueModelLensAgent = revenueModelLensAgent;
        this.candidateExpander = candidateExpander;
    }

    public List<InsightCandidate> generateAll(
            List<CollectedData> collected,
            List<AgentDashboardResult.KpiCard> kpiCards,
            List<AgentDashboardResult.Anomaly> anomalies,
            List<SignalEntity> signals) {

        List<InsightCandidate> all = new ArrayList<>();
        all.addAll(revenueModelLensAgent.generate(collected));
        all.addAll(generalDiscoveryLensAgent.generate(collected));
        all.addAll(growthLensAgent.generate(collected, kpiCards, signals));
        all.addAll(riskLensAgent.generate(collected, anomalies, signals));
        all.addAll(efficiencyLensAgent.generate(collected));
        all.addAll(customerLensAgent.generate(collected));
        return candidateExpander.supplement(all, collected, kpiCards, anomalies);
    }
}
