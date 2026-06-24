package com.example.BACKEND.catalogue.agent.scale;

import com.example.BACKEND.catalogue.repository.CatalogueSnapshotRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class MetricRollupScheduler {

    private final CatalogueSnapshotRepository snapshotRepo;
    private final MetricRollupService metricRollupService;
    private final ScaleProperties properties;

    public MetricRollupScheduler(CatalogueSnapshotRepository snapshotRepo,
                                 MetricRollupService metricRollupService,
                                 ScaleProperties properties) {
        this.snapshotRepo = snapshotRepo;
        this.metricRollupService = metricRollupService;
        this.properties = properties;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void rebuildRollupsNightly() {
        if (!properties.isRollupEnabled()) return;

        List<String> clients = snapshotRepo.findAllClientIds();
        System.out.printf("[MetricRollupScheduler] %s — rebuilding rollups for %d tenant(s)%n",
                LocalDateTime.now(), clients.size());

        for (String clientId : clients) {
            try {
                metricRollupService.buildRollupsForClient(clientId);
            } catch (Exception e) {
                System.out.printf("[MetricRollupScheduler] Failed for %s: %s%n",
                        clientId, e.getMessage());
            }
        }
    }
}
