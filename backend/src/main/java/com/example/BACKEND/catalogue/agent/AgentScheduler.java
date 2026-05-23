package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.repository.CatalogueSnapshotRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Runs the AgentOrchestrator automatically every 6 hours for every tenant
 * that has an approved catalogue.
 *
 * This is what makes Kontexa proactive — users see fresh insights when they
 * log in without needing to click "Refresh Insights" each time.
 *
 * Flow:
 *   1. Load all clientIds from catalogue_snapshots
 *   2. For each client, check readiness via SignalReadinessChecker
 *   3. If ready, run AgentOrchestrator.analyse(clientId)
 *   4. Log success / failure per client
 *
 * Enable by adding spring.task.scheduling.enabled=true to application.properties.
 * The @EnableScheduling annotation is on the main application class.
 */
@Component
public class AgentScheduler {

    private final AgentOrchestrator           orchestrator;
    private final SignalReadinessChecker      readinessChecker;
    private final CatalogueSnapshotRepository snapshotRepo;

    public AgentScheduler(
            AgentOrchestrator           orchestrator,
            SignalReadinessChecker      readinessChecker,
            CatalogueSnapshotRepository snapshotRepo
    ) {
        this.orchestrator     = orchestrator;
        this.readinessChecker = readinessChecker;
        this.snapshotRepo     = snapshotRepo;
    }

    /**
     * Runs every 6 hours (21 600 000 ms).
     * fixedDelay ensures the next run starts only after the previous one completes,
     * preventing overlapping runs on slow tenants.
     */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void runScheduledAnalysis() {
        List<String> clients = snapshotRepo.findAllClientIds();
        if (clients.isEmpty()) return;

        System.out.printf("[AgentScheduler] %s — starting scheduled analysis for %d tenant(s)%n",
                LocalDateTime.now(), clients.size());

        int succeeded = 0;
        int skipped   = 0;
        int failed    = 0;

        for (String clientId : clients) {
            try {
                SignalReadinessChecker.ReadinessReport report = readinessChecker.check(clientId);
                if (!report.ready()) {
                    System.out.printf("[AgentScheduler] Skipping %s — %s%n", clientId, report.summary());
                    skipped++;
                    continue;
                }

                AgentDashboardResult result = orchestrator.analyse(clientId);
                int cardCount = result.getInsights() == null ? 0 : result.getInsights().size();
                System.out.printf("[AgentScheduler] %s — %d insight card(s) generated%n",
                        clientId, cardCount);
                succeeded++;

            } catch (Exception e) {
                System.out.printf("[AgentScheduler] Failed for %s: %s%n", clientId, e.getMessage());
                failed++;
            }
        }

        System.out.printf("[AgentScheduler] Done — succeeded=%d skipped=%d failed=%d%n",
                succeeded, skipped, failed);
    }
}
