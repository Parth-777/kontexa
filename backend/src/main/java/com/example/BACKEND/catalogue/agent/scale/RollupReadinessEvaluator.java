package com.example.BACKEND.catalogue.agent.scale;

import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.catalogue.repository.DailyMetricRollupRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Decides whether agents can read pre-aggregated rollups instead of scanning the warehouse.
 * Implements readiness rules R1 (freshness) and R4 (minimum row coverage) from the scale spec.
 */
@Component
public class RollupReadinessEvaluator {

    private final DailyMetricRollupRepository rollupRepository;
    private final ScaleProperties properties;

    public RollupReadinessEvaluator(DailyMetricRollupRepository rollupRepository,
                                    ScaleProperties properties) {
        this.rollupRepository = rollupRepository;
        this.properties = properties;
    }

    public boolean isReady(TableContext ctx) {
        if (!properties.isRollupEnabled()) return false;
        if (ctx.tier() != ScaleTier.LARGE) return false;
        if (!"FACT".equalsIgnoreCase(ctx.tableRole())) return false;
        if (ctx.hints().dateCol() == null || ctx.hints().numericCols().isEmpty()) return false;

        String clientId = ctx.clientId();
        String tableName = ctx.hints().tableName();

        LocalDateTime builtAt = rollupRepository.findLatestBuiltAt(clientId, tableName);
        if (builtAt == null) return false;

        long ageHours = Duration.between(builtAt, LocalDateTime.now()).toHours();
        if (ageHours > properties.getRollupMaxAgeHours()) return false;

        long rowCount = rollupRepository.countByClientIdAndTableName(clientId, tableName);
        if (rowCount < 7) return false;

        int windowDays = properties.windowDays(ScaleTier.LARGE);
        long expectedMin = (long) (windowDays * ctx.hints().numericCols().size() * 0.85);
        return rowCount >= expectedMin;
    }
}
