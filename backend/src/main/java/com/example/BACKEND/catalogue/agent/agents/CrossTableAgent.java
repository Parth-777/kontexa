package com.example.BACKEND.catalogue.agent.agents;

import com.example.BACKEND.catalogue.agent.CollectedData;
import com.example.BACKEND.catalogue.agent.KpiDetectorService;
import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.catalogue.agent.scale.AgentSqlHelper;
import com.example.BACKEND.catalogue.agent.scale.AnalysisRunContext;
import com.example.BACKEND.catalogue.agent.scale.AnalysisWindow;
import com.example.BACKEND.catalogue.agent.scale.AnalysisWindowFactory;
import com.example.BACKEND.catalogue.agent.scale.ColumnSelector;
import com.example.BACKEND.catalogue.agent.scale.ScaleAwareQueryExecutor;
import com.example.BACKEND.catalogue.agent.scale.ScaleTier;
import com.example.BACKEND.catalogue.agent.scale.TableScalePolicy;
import com.example.BACKEND.catalogue.entity.CatalogueTableRelationEntity;
import com.example.BACKEND.catalogue.repository.CatalogueTableRelationRepository;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CrossTableAgent {

    private static final int TOP_N       = 15;
    private static final int MAX_JOINS   = 5;
    private static final int MAX_METRICS = 2;
    private static final int MAX_DIMS    = 2;

    private final CatalogueTableRelationRepository relationRepository;
    private final ScaleAwareQueryExecutor          queryExecutor;
    private final TableScalePolicy                   tableScalePolicy;
    private final AnalysisWindowFactory              analysisWindowFactory;
    private final KpiDetectorService                 kpiDetector;

    public CrossTableAgent(CatalogueTableRelationRepository relationRepository,
                           ScaleAwareQueryExecutor queryExecutor,
                           TableScalePolicy tableScalePolicy,
                           AnalysisWindowFactory analysisWindowFactory,
                           KpiDetectorService kpiDetector) {
        this.relationRepository    = relationRepository;
        this.queryExecutor         = queryExecutor;
        this.tableScalePolicy      = tableScalePolicy;
        this.analysisWindowFactory = analysisWindowFactory;
        this.kpiDetector           = kpiDetector;
    }

    public List<CollectedData> collectData(
            String clientId,
            JsonNode catalogueNode,
            String provider,
            boolean useBQ,
            boolean useSF,
            Optional<TenantCloudConnectionService.BigQueryConfig>  bqCfg,
            Optional<TenantCloudConnectionService.SnowflakeConfig> sfCfg,
            JdbcTemplate jdbcTemplate,
            AnalysisRunContext runContext
    ) {
        List<CollectedData> results = new ArrayList<>();

        List<CatalogueTableRelationEntity> relations =
                limit(relationRepository.findByClientId(clientId), MAX_JOINS);

        if (relations.isEmpty()) return results;

        for (CatalogueTableRelationEntity rel : relations) {
            if (runContext != null && !runContext.canRunQuery()) break;

            String factTable = rel.getFactTable();
            String dimTable  = rel.getDimensionTable();
            String joinKey   = rel.getJoinKey();

            JsonNode factNode = findTableNode(catalogueNode, factTable);
            if (factNode == null) continue;

            KpiDetectorService.ColumnHints factHints = kpiDetector.classifyColumns(factNode);
            long factRows = factNode.path("rowCount").asLong(0);
            ScaleTier tier = tableScalePolicy.tier(factRows);

            if (tier == ScaleTier.LARGE && factHints.dateCol() == null) continue;

            AnalysisWindow window = analysisWindowFactory.forTable(factHints, Map.of(), tier, null);
            String factRef = buildTableRef(factTable, rel.getFactTableSchema(), provider);
            String dimRef  = buildTableRef(dimTable, rel.getDimensionTableSchema(), provider);

            List<String> metrics = ColumnSelector.selectMetrics(
                    factHints.numericCols(), Map.of(), MAX_METRICS);
            List<String> dims = findStringCols(catalogueNode, dimTable, MAX_DIMS);

            if (metrics.isEmpty() || dims.isEmpty()) continue;

            TableContext factCtx = new TableContext(
                    clientId, factHints, Map.of(), factRef, provider,
                    useBQ, useSF, bqCfg, sfCfg, jdbcTemplate,
                    tier, factRows, window, runContext, "FACT");

            for (String metric : metrics) {
                for (String dim : dims) {
                    String sql = buildJoinSql(factCtx, dimRef, joinKey, metric, dim);
                    String label = "Cross-table: " + factTable + " × " + dimTable
                            + " (" + metric + " by " + dim + ")";
                    List<Map<String, Object>> rows = queryExecutor.execute(sql, label, factCtx);
                    if (!rows.isEmpty()) {
                        results.add(new CollectedData(label, sql, rows));
                    }
                }
            }
        }

        return results;
    }

    private String buildJoinSql(TableContext factCtx, String dimRef, String joinKey,
                                 String metric, String dim) {
        String provider = factCtx.provider();
        boolean isBQ = "bigquery".equalsIgnoreCase(provider);

        String fAlias   = "f";
        String dAlias   = "d";
        String metRef   = isBQ ? fAlias + ".`" + metric + "`" : fAlias + "." + metric;
        String dimRef2  = isBQ ? dAlias + ".`" + dim    + "`" : dAlias + "." + dim;
        String fkRef    = isBQ ? fAlias + ".`" + joinKey + "`" : fAlias + "." + joinKey;
        String dimPkExpr = dAlias + ".id";

        String extraWindow = "";
        if (factCtx.hints().dateCol() != null && factCtx.window() != null && factCtx.window().active()) {
            String dateRef = isBQ
                    ? fAlias + ".`" + factCtx.hints().dateCol() + "`"
                    : fAlias + "." + factCtx.hints().dateCol();
            extraWindow = factCtx.window().whereClause(dateRef, provider)
                    .replace(" WHERE ", " AND ");
        }

        return String.format(
                "SELECT %s AS dimension_value, SUM(%s) AS total " +
                "FROM %s %s JOIN %s %s ON %s = %s " +
                "WHERE %s IS NOT NULL AND %s IS NOT NULL%s " +
                "GROUP BY dimension_value ORDER BY total DESC LIMIT %d",
                dimRef2, metRef,
                factCtx.tableRef(), fAlias, dimRef, dAlias, fkRef, dimPkExpr,
                dimRef2, metRef, extraWindow,
                TOP_N
        );
    }

    private JsonNode findTableNode(JsonNode catalogueNode, String tableName) {
        for (JsonNode tableNode : catalogueNode.path("tables")) {
            if (tableName.equalsIgnoreCase(tableNode.path("tableName").asText(""))) {
                return tableNode;
            }
        }
        return null;
    }

    private List<String> findStringCols(JsonNode catalogueNode, String tableName, int max) {
        List<String> cols = new ArrayList<>();
        for (JsonNode tableNode : catalogueNode.path("tables")) {
            if (!tableName.equalsIgnoreCase(tableNode.path("tableName").asText(""))) continue;
            ScaleTier tier = tableScalePolicy.tier(tableNode.path("rowCount").asLong(0));
            for (JsonNode col : tableNode.path("columns")) {
                String role = col.path("role").asText("").toLowerCase();
                String type = col.path("dataType").asText("").toLowerCase();
                boolean isString = type.contains("varchar") || type.contains("text")
                        || type.contains("string") || type.contains("char");
                if ("dimension".equals(role) || isString) {
                    String name = col.path("columnName").asText("");
                    if (!name.isBlank() && !name.equalsIgnoreCase("id"))
                        cols.add(name);
                }
            }
            return ColumnSelector.selectDimensions(cols, tier, max);
        }
        return cols;
    }

    private String buildTableRef(String tableName, String tableSchema, String provider) {
        return switch (provider == null ? "" : provider.toLowerCase()) {
            case "bigquery"  -> tableName;
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

    private <T> List<T> limit(List<T> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }
}
