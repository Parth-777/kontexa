package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.agent.scale.ScaleProperties;
import com.example.BACKEND.catalogue.repository.CatalogueSnapshotRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AgentScheduler {

    private final AgentOrchestrator           orchestrator;
    private final SignalReadinessChecker      readinessChecker;
    private final CatalogueSnapshotRepository snapshotRepo;
    private final ScaleProperties             scaleProperties;

    public AgentScheduler(
            AgentOrchestrator           orchestrator,
            SignalReadinessChecker      readinessChecker,
            CatalogueSnapshotRepository snapshotRepo,
            ScaleProperties             scaleProperties
    ) {
        this.orchestrator     = orchestrator;
        this.readinessChecker = readinessChecker;
        this.snapshotRepo     = snapshotRepo;
        this.scaleProperties  = scaleProperties;
    }

    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void runScheduledAnalysis() {
        List<String> clients = snapshotRepo.findAllClientIds();
        if (clients.isEmpty()) return;

        int parallelism = Math.max(1, scaleProperties.getSchedulerParallelTenants());
        long timeoutMinutes = scaleProperties.getSchedulerTenantTimeoutMinutes();

        System.out.printf("[AgentScheduler] %s — starting scheduled analysis for %d tenant(s), parallelism=%d%n",
                LocalDateTime.now(), clients.size(), parallelism);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger skipped   = new AtomicInteger();
        AtomicInteger failed    = new AtomicInteger();
        AtomicInteger timedOut  = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(parallelism)) {
            List<? extends Future<?>> futures = clients.stream()
                    .map(clientId -> pool.submit(() ->
                            analyseTenant(clientId, succeeded, skipped, failed, timedOut)))
                    .toList();

            for (Future<?> future : futures) {
                try {
                    future.get(timeoutMinutes, TimeUnit.MINUTES);
                } catch (java.util.concurrent.TimeoutException e) {
                    future.cancel(true);
                    timedOut.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
            }
        }

        System.out.printf("[AgentScheduler] Done — succeeded=%d skipped=%d failed=%d timedOut=%d%n",
                succeeded.get(), skipped.get(), failed.get(), timedOut.get());
    }

    private void analyseTenant(String clientId,
                                AtomicInteger succeeded,
                                AtomicInteger skipped,
                                AtomicInteger failed,
                                AtomicInteger timedOut) {
        try {
            SignalReadinessChecker.ReadinessReport report = readinessChecker.check(clientId);
            if (!report.ready()) {
                System.out.printf("[AgentScheduler] Skipping %s — %s%n", clientId, report.summary());
                skipped.incrementAndGet();
                return;
            }

            AgentDashboardResult result = orchestrator.analyse(clientId);
            int cardCount = result.getInsights() == null ? 0 : result.getInsights().size();
            System.out.printf("[AgentScheduler] %s — %d insight card(s) generated%n",
                    clientId, cardCount);
            succeeded.incrementAndGet();

        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                System.out.printf("[AgentScheduler] Timed out for %s%n", clientId);
                timedOut.incrementAndGet();
            } else {
                System.out.printf("[AgentScheduler] Failed for %s: %s%n", clientId, e.getMessage());
                failed.incrementAndGet();
            }
        }
    }
}
