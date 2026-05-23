package com.example.BACKEND.catalogue.agent.agents;

import com.example.BACKEND.catalogue.agent.CollectedData;
import com.example.BACKEND.catalogue.agent.KpiDetectorService;
import com.example.BACKEND.catalogue.entity.CatalogueTableRelationEntity;
import com.example.BACKEND.catalogue.repository.CatalogueTableRelationRepository;
import com.example.BACKEND.tenant.BigQueryConnectorService;
import com.example.BACKEND.tenant.SnowflakeConnectorService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Analyses relationships between tables using the detected joins from StarSchemaDetector.
 *
 * This is what makes Kontexa a true Tableau replacement:
 *   - Tableau shows: "bar chart of revenue by product category"
 *   - Kontexa shows: "Electronics drives 48% of revenue with only 12% of catalogue volume"
 *
 * For each join in catalogue_table_relations, it runs:
 *   SELECT dim.{dim_col}, AGG(fact.{metric}) AS total
 *   FROM {fact_table}
 *   JOIN {dim_table} ON fact.{join_key} = dim.id
 *   GROUP BY dim.{dim_col}
 *   ORDER BY total DESC
 *   LIMIT 15
 *
 * Results are labelled "Cross-table: {fact} × {dim}" so the LLM knows
 * they represent a dimensional breakdown across two tables.
 */
@Service
public class CrossTableAgent {

    private static final int TOP_N       = 15;
    private static final int MAX_JOINS   = 5;
    private static final int MAX_METRICS = 2;
    private static final int MAX_DIMS    = 2;

    private final CatalogueTableRelationRepository relationRepository;
    private final BigQueryConnectorService         bigQueryConnectorService;
    private final SnowflakeConnectorService        snowflakeConnectorService;

    public CrossTableAgent(CatalogueTableRelationRepository relationRepository,
                           BigQueryConnectorService         bigQueryConnectorService,
                           SnowflakeConnectorService        snowflakeConnectorService) {
        this.relationRepository    = relationRepository;
        this.bigQueryConnectorService  = bigQueryConnectorService;
        this.snowflakeConnectorService = snowflakeConnectorService;
    }

    /**
     * Runs cross-table join analysis for a client.
     * Needs the full catalogue node to resolve dimension columns on the joined table.
     */
    public List<CollectedData> collectData(
            String clientId,
            JsonNode catalogueNode,
            String provider,
            boolean useBQ,
            boolean useSF,
            Optional<TenantCloudConnectionService.BigQueryConfig>  bqCfg,
            Optional<TenantCloudConnectionService.SnowflakeConfig> sfCfg,
            JdbcTemplate jdbcTemplate
    ) {
        List<CollectedData> results = new ArrayList<>();

        List<CatalogueTableRelationEntity> relations =
                limit(relationRepository.findByClientId(clientId), MAX_JOINS);

        if (relations.isEmpty()) return results;

        for (CatalogueTableRelationEntity rel : relations) {
            String factTable = rel.getFactTable();
            String dimTable  = rel.getDimensionTable();
            String joinKey   = rel.getJoinKey();

            String factRef = buildTableRef(factTable, rel.getFactTableSchema(), provider);
            String dimRef  = buildTableRef(dimTable, rel.getDimensionTableSchema(), provider);

            // Find metric columns on the fact table and dimension columns on the dim table
            List<String> metrics = findNumericCols(catalogueNode, factTable);
            List<String> dims    = findStringCols(catalogueNode, dimTable);

            if (metrics.isEmpty() || dims.isEmpty()) continue;

            // Run one join query per metric × dim combination (capped)
            for (String metric : limit(metrics, MAX_METRICS)) {
                for (String dim : limit(dims, MAX_DIMS)) {
                    String sql = buildJoinSql(factRef, dimRef, joinKey, metric, dim, dimTable, provider);
                    String label = "Cross-table: " + factTable + " × " + dimTable
                            + " (" + metric + " by " + dim + ")";
                    safeExecute(sql, label, provider, useBQ, useSF, bqCfg, sfCfg, jdbcTemplate, results);
                }
            }
        }

        return results;
    }

    // ── SQL builder ───────────────────────────────────────────────────────────

    private String buildJoinSql(String factRef, String dimRef, String joinKey,
                                 String metric, String dim, String dimTable, String provider) {
        boolean isBQ = "bigquery".equalsIgnoreCase(provider);

        String fAlias   = "f";
        String dAlias   = "d";
        String metRef   = isBQ ? fAlias + ".`" + metric + "`" : fAlias + "." + metric;
        String dimRef2  = isBQ ? dAlias + ".`" + dim    + "`" : dAlias + "." + dim;
        String fkRef    = isBQ ? fAlias + ".`" + joinKey + "`" : fAlias + "." + joinKey;

        // Try joining on dim.id first, then dim.{table}_id, then dim.{joinKey without _id/fk}
        String dimPkExpr = dAlias + ".id";

        return String.format(
                "SELECT %s AS dimension_value, SUM(%s) AS total " +
                "FROM %s %s JOIN %s %s ON %s = %s " +
                "WHERE %s IS NOT NULL AND %s IS NOT NULL " +
                "GROUP BY dimension_value ORDER BY total DESC LIMIT %d",
                dimRef2, metRef,
                factRef, fAlias, dimRef, dAlias, fkRef, dimPkExpr,
                dimRef2, metRef,
                TOP_N
        );
    }

    // ── Catalogue helpers ─────────────────────────────────────────────────────

    private List<String> findNumericCols(JsonNode catalogueNode, String tableName) {
        List<String> cols = new ArrayList<>();
        for (JsonNode tableNode : catalogueNode.path("tables")) {
            if (!tableName.equalsIgnoreCase(tableNode.path("tableName").asText(""))) continue;
            for (JsonNode col : tableNode.path("columns")) {
                String role = col.path("role").asText("").toLowerCase();
                String agg  = col.path("aggregationMethod").asText("NONE");
                if ("metric".equals(role) || List.of("SUM","COUNT","AVG").contains(agg)) {
                    cols.add(col.path("columnName").asText(""));
                }
            }
            break;
        }
        return cols.stream().filter(s -> !s.isBlank()).toList();
    }

    private List<String> findStringCols(JsonNode catalogueNode, String tableName) {
        List<String> cols = new ArrayList<>();
        for (JsonNode tableNode : catalogueNode.path("tables")) {
            if (!tableName.equalsIgnoreCase(tableNode.path("tableName").asText(""))) continue;
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
            break;
        }
        return cols;
    }

    // ── Table ref builder ─────────────────────────────────────────────────────

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

    // ── Query execution ───────────────────────────────────────────────────────

    private void safeExecute(String sql, String label, String provider,
                              boolean useBQ, boolean useSF,
                              Optional<TenantCloudConnectionService.BigQueryConfig>  bqCfg,
                              Optional<TenantCloudConnectionService.SnowflakeConfig> sfCfg,
                              JdbcTemplate jdbcTemplate,
                              List<CollectedData> out) {
        try {
            List<Map<String, Object>> rows;
            if (useBQ && bqCfg.isPresent()) {
                var c = bqCfg.get();
                rows = bigQueryConnectorService.executeSelect(
                        c.projectId(), c.serviceAccountJson(), c.location(), c.dataset(), sql);
            } else if (useSF && sfCfg.isPresent()) {
                var c = sfCfg.get();
                rows = snowflakeConnectorService.executeSelect(
                        c.account(), c.warehouse(), c.database(),
                        c.schema(), c.username(), c.password(), sql);
            } else {
                rows = jdbcTemplate.queryForList(sql);
            }
            if (rows != null && !rows.isEmpty()) {
                out.add(new CollectedData(label, sql, rows));
            }
        } catch (Exception e) {
            System.out.printf("[CrossTableAgent] Query failed [%s]: %s%n", label, e.getMessage());
        }
    }

    private <T> List<T> limit(List<T> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }
}
