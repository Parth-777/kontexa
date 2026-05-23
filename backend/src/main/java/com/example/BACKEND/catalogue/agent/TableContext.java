package com.example.BACKEND.catalogue.agent;

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
        JdbcTemplate jdbcTemplate
) {}
