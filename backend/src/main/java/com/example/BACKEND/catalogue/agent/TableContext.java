package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.agent.scale.AnalysisRunContext;
import com.example.BACKEND.catalogue.agent.scale.AnalysisWindow;
import com.example.BACKEND.catalogue.agent.scale.ScaleTier;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;

/**
 * Bundles everything an agent needs to query one table.
 * Created once per table by AgentOrchestrator and passed to each agent.
 */
public record TableContext(
        String clientId,
        KpiDetectorService.ColumnHints hints,
        Map<String, EnrichedColInfo> enriched,
        String tableRef,
        String provider,
        boolean useBQ,
        boolean useSF,
        Optional<TenantCloudConnectionService.BigQueryConfig>  bqCfg,
        Optional<TenantCloudConnectionService.SnowflakeConfig> sfCfg,
        JdbcTemplate jdbcTemplate,
        ScaleTier tier,
        long rowCount,
        AnalysisWindow window,
        AnalysisRunContext runContext,
        String tableRole
) {
    /** Backward-compatible constructor without scale fields (defaults to SMALL, unrestricted). */
    public TableContext(
            String clientId,
            KpiDetectorService.ColumnHints hints,
            Map<String, EnrichedColInfo> enriched,
            String tableRef,
            String provider,
            boolean useBQ,
            boolean useSF,
            Optional<TenantCloudConnectionService.BigQueryConfig> bqCfg,
            Optional<TenantCloudConnectionService.SnowflakeConfig> sfCfg,
            JdbcTemplate jdbcTemplate
    ) {
        this(clientId, hints, enriched, tableRef, provider, useBQ, useSF, bqCfg, sfCfg, jdbcTemplate,
                ScaleTier.SMALL, 0L, AnalysisWindow.unrestricted(),
                AnalysisRunContext.unlimited(), null);
    }
}
