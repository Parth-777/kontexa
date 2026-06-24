package com.example.BACKEND.catalogue.agent.scale;

import com.example.BACKEND.catalogue.agent.EnrichedColInfo;
import com.example.BACKEND.catalogue.agent.KpiDetectorService;
import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds tier-aware {@link TableContext} for any agent or service that queries warehouse tables.
 */
@Component
public class TableContextFactory {

    private final KpiDetectorService kpiDetector;
    private final TableScalePolicy tableScalePolicy;
    private final AnalysisWindowFactory analysisWindowFactory;

    public TableContextFactory(
            KpiDetectorService kpiDetector,
            TableScalePolicy tableScalePolicy,
            AnalysisWindowFactory analysisWindowFactory
    ) {
        this.kpiDetector = kpiDetector;
        this.tableScalePolicy = tableScalePolicy;
        this.analysisWindowFactory = analysisWindowFactory;
    }

    public TableContext forTableNode(
            String clientId,
            JsonNode tableNode,
            String provider,
            boolean useBQ,
            boolean useSF,
            Optional<TenantCloudConnectionService.BigQueryConfig> bqCfg,
            Optional<TenantCloudConnectionService.SnowflakeConfig> sfCfg,
            JdbcTemplate jdbcTemplate,
            AnalysisRunContext runContext
    ) {
        KpiDetectorService.ColumnHints rawHints = kpiDetector.classifyColumns(tableNode);
        long rowCount = tableNode.path("rowCount").asLong(0);
        ScaleTier tier = tableScalePolicy.tier(rowCount);

        Map<String, EnrichedColInfo> enriched = buildEnrichedMap(tableNode);
        String tableRef = buildTableRef(rawHints.tableName(), rawHints.tableSchema(), provider);
        String tableRole = tableNode.path("tableRole").asText(null);

        TableContext windowProbe = new TableContext(
                clientId, rawHints, enriched, tableRef, provider,
                useBQ, useSF, bqCfg, sfCfg, jdbcTemplate,
                tier, rowCount, AnalysisWindow.unrestricted(),
                runContext, tableRole);
        AnalysisWindow window = analysisWindowFactory.forTable(rawHints, enriched, tier, windowProbe);

        List<String> metrics = ColumnSelector.selectMetrics(
                rawHints.numericCols(), enriched, tableScalePolicy.properties().maxMetrics(tier));
        List<String> dims = ColumnSelector.selectDimensions(
                rawHints.stringCols(), tier, tableScalePolicy.properties().maxDimensions(tier));

        KpiDetectorService.ColumnHints hints = new KpiDetectorService.ColumnHints(
                rawHints.tableName(), rawHints.tableSchema(),
                rawHints.dateCol(), dims, metrics);

        return new TableContext(
                clientId, hints, enriched, tableRef, provider,
                useBQ, useSF, bqCfg, sfCfg, jdbcTemplate,
                tier, rowCount, window, runContext, tableRole);
    }

    /** Largest rowCount among tables in the catalogue snapshot. */
    public long maxRowCount(JsonNode catalogueNode) {
        long max = 0;
        for (JsonNode t : catalogueNode.path("tables")) {
            max = Math.max(max, t.path("rowCount").asLong(0));
        }
        return max;
    }

    public ScaleTier tierForRowCount(long rowCount) {
        return tableScalePolicy.tier(rowCount);
    }

    private Map<String, EnrichedColInfo> buildEnrichedMap(JsonNode tableNode) {
        Map<String, EnrichedColInfo> map = new HashMap<>();
        for (JsonNode col : tableNode.path("columns")) {
            String colName = col.path("columnName").asText("");
            if (colName.isBlank()) continue;
            map.put(colName.toLowerCase(), new EnrichedColInfo(
                    col.path("aggregationMethod").asText("NONE"),
                    col.path("comparisonPeriod").asText("NONE"),
                    col.path("dateGranularity").asText("N/A"),
                    col.path("businessMeaning").asText(""),
                    col.path("maxValue").asText("")
            ));
        }
        return map;
    }

    public static String buildTableRef(String tableName, String tableSchema, String provider) {
        return switch (provider == null ? "" : provider.toLowerCase()) {
            case "bigquery" -> tableName;
            case "snowflake" -> {
                String sc = tableSchema == null || tableSchema.isBlank()
                        ? "PUBLIC" : tableSchema.toUpperCase();
                yield sc + "." + tableName.toUpperCase();
            }
            default -> {
                String sc = tableSchema == null || tableSchema.isBlank()
                        ? "public" : tableSchema;
                yield sc + "." + tableName;
            }
        };
    }
}
