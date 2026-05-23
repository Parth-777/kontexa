package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.entity.CatalogueColumnEntity;
import com.example.BACKEND.catalogue.entity.CatalogueTableEntity;
import com.example.BACKEND.catalogue.entity.CatalogueTableRelationEntity;
import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.example.BACKEND.catalogue.repository.CatalogueTableRelationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Performs COLUMNAR classification of every column in every catalogue table,
 * then infers JOIN relationships between tables that share FK–PK patterns.
 *
 * ── Key principle ─────────────────────────────────────────────────────────────
 *
 *  Classification is at the COLUMN level, not the table level.
 *  Most real-world tables (orders, transactions, events) contain BOTH:
 *    - Fact columns   : amount, quantity, revenue, duration  (numeric measures)
 *    - Dimension cols : region, category, status, channel    (categorical groupers)
 *    - Date columns   : order_date, created_at               (temporal)
 *
 *  The column `role` field is set to:
 *    "metric"    — numeric measure (fact column)
 *    "dimension" — categorical grouper (dimension column)
 *    "timestamp" — temporal column
 *    (existing non-blank roles set by the tenant are preserved)
 *
 * ── Table-level tableRole ────────────────────────────────────────────────────
 *
 *  tableRole is a routing HINT for the agent orchestrator, not a hard category:
 *    MIXED             : has both fact and dimension columns (most common)
 *    FACT_DOMINANT     : >70% numeric columns
 *    DIMENSION_DOMINANT: >70% string/categorical columns
 *    UNKNOWN           : empty or unclassifiable
 *
 * ── Join detection ───────────────────────────────────────────────────────────
 *
 *  For every FK column (ends in _id/_fk, not the table's own PK), extract the
 *  referenced entity and look for a table with a matching name.
 *  Result stored in catalogue_table_relations with confidence = LIKELY.
 */
@Service
public class StarSchemaDetector {

    private static final Set<String> NUMERIC_TYPES = Set.of(
            "int", "integer", "bigint", "smallint", "tinyint",
            "float", "double", "real", "numeric", "decimal",
            "number", "money", "currency", "long"
    );

    private static final Set<String> STRING_TYPES = Set.of(
            "varchar", "char", "text", "string", "nvarchar", "nchar",
            "clob", "enum", "category", "boolean", "bool"
    );

    private static final Set<String> DATE_TYPES = Set.of(
            "date", "datetime", "timestamp", "time"
    );

    private final CatalogueTableRelationRepository relationRepo;

    public StarSchemaDetector(CatalogueTableRelationRepository relationRepo) {
        this.relationRepo = relationRepo;
    }

    // ── Public entry point ────────────────────────────────────────────────────

    @Transactional
    public void detect(ClientCatalogueEntity catalogue) {
        List<CatalogueTableEntity> tables = catalogue.getTables();
        if (tables == null || tables.isEmpty()) return;

        // Pass 1: classify every column in every table, then set the table routing hint
        for (CatalogueTableEntity table : tables) {
            classifyColumns(table);
            String hint = deriveTableRole(table);
            table.setTableRole(hint);
            System.out.printf("[StarSchema] %s → %s%n", table.getTableName(), hint);
        }

        // Pass 2: detect joins and persist (replace existing relations for this client)
        String clientId = catalogue.getClientId();
        relationRepo.deleteByClientId(clientId);

        List<CatalogueTableRelationEntity> relations = detectJoins(clientId, tables);
        if (!relations.isEmpty()) {
            relationRepo.saveAll(relations);
            System.out.printf("[StarSchema] %d relation(s) stored for %s%n",
                    relations.size(), clientId);
        }
    }

    // ── Column-level classification ───────────────────────────────────────────

    /**
     * Classifies each column in the table and sets its role field.
     * Only sets the role when it is blank or "unknown" — tenant-set roles are preserved.
     */
    private void classifyColumns(CatalogueTableEntity table) {
        if (table.getColumns() == null) return;
        String tableNameLower = table.getTableName().toLowerCase();

        for (CatalogueColumnEntity col : table.getColumns()) {
            // Preserve any role the tenant or enricher has already set
            String existingRole = col.getRole();
            if (existingRole != null && !existingRole.isBlank()
                    && !existingRole.equalsIgnoreCase("unknown")) {
                continue;
            }

            String name = col.getColumnName().toLowerCase();
            String type = col.getDataType()         == null ? "" : col.getDataType().toLowerCase();
            String agg  = col.getAggregationMethod() == null ? "NONE" : col.getAggregationMethod();
            String dg   = col.getDateGranularity()   == null ? "N/A"  : col.getDateGranularity();

            if (isDate(type, "", dg)) {
                col.setRole("timestamp");
            } else if (isNumeric(type, "", agg)) {
                col.setRole("metric");
            } else if (isForeignKey(name, tableNameLower) || isPrimaryKey(name, tableNameLower)) {
                col.setRole("identifier");
            } else {
                col.setRole("dimension");
            }
        }
    }

    /**
     * Derives the table routing hint based on its classified columns.
     * This is a HINT for the orchestrator, not a strict exclusion.
     * Most real-world tables will be MIXED.
     */
    private String deriveTableRole(CatalogueTableEntity table) {
        if (table.getColumns() == null || table.getColumns().isEmpty()) return "UNKNOWN";

        int total   = table.getColumns().size();
        int metrics = 0;
        int dims    = 0;

        for (CatalogueColumnEntity col : table.getColumns()) {
            String r = col.getRole();
            if ("metric".equals(r))    metrics++;
            else if ("dimension".equals(r)) dims++;
        }

        if (total == 0) return "UNKNOWN";

        double metricRatio = (double) metrics / total;
        double dimRatio    = (double) dims    / total;

        if (metricRatio > 0.70) return "FACT_DOMINANT";
        if (dimRatio    > 0.70) return "DIMENSION_DOMINANT";
        if (metrics > 0 && dims > 0) return "MIXED";
        if (metrics > 0) return "FACT_DOMINANT";
        if (dims    > 0) return "DIMENSION_DOMINANT";

        return "UNKNOWN";
    }

    // ── Join detection ────────────────────────────────────────────────────────

    private List<CatalogueTableRelationEntity> detectJoins(
            String clientId, List<CatalogueTableEntity> tables) {

        List<CatalogueTableRelationEntity> relations = new ArrayList<>();

        // Any table can have FK columns pointing to another table —
        // we don't restrict to FACT_DOMINANT tables only
        for (CatalogueTableEntity fact : tables) {
            String factNameLower = fact.getTableName().toLowerCase();

            for (CatalogueColumnEntity col : fact.getColumns()) {
                String colName = col.getColumnName().toLowerCase();
                if (!isForeignKey(colName, factNameLower)) continue;

                String entity = extractEntity(colName);
                if (entity == null || entity.isBlank()) continue;

                CatalogueTableEntity match = findDimensionMatch(entity, tables);
                if (match == null) continue;

                CatalogueTableRelationEntity rel = new CatalogueTableRelationEntity();
                rel.setClientId(clientId);
                rel.setFactTable(fact.getTableName());
                rel.setFactTableSchema(fact.getTableSchema());
                rel.setDimensionTable(match.getTableName());
                rel.setDimensionTableSchema(match.getTableSchema());
                rel.setJoinKey(col.getColumnName());
                rel.setConfidence("LIKELY");
                rel.setCreatedAt(LocalDateTime.now());
                relations.add(rel);

                System.out.printf("[StarSchema] Join: %s → %s via %s%n",
                        fact.getTableName(), match.getTableName(), col.getColumnName());
            }
        }
        return relations;
    }

    private CatalogueTableEntity findDimensionMatch(String entity,
                                                    List<CatalogueTableEntity> tables) {
        for (String candidate : List.of(
                entity, entity + "s", entity + "es",
                "dim_" + entity, entity + "_dim", "tbl_" + entity)) {
            for (CatalogueTableEntity t : tables) {
                if (t.getTableName().equalsIgnoreCase(candidate)) return t;
            }
        }
        return null;
    }

    // ── Column type helpers ───────────────────────────────────────────────────

    private boolean isNumeric(String type, String role, String agg) {
        if (List.of("SUM", "COUNT", "AVG", "LAST_VALUE").contains(agg)) return true;
        if ("metric".equals(role)) return true;
        return NUMERIC_TYPES.stream().anyMatch(type::contains);
    }

    private boolean isDate(String type, String role, String dateGranularity) {
        if ("timestamp".equals(role)) return true;
        if (!"N/A".equals(dateGranularity) && !dateGranularity.isBlank()) return true;
        return DATE_TYPES.stream().anyMatch(type::contains);
    }

    /**
     * A FK column ends in _id or _fk and is NOT the table's own surrogate PK.
     * e.g. "product_id" on orders table → FK; "order_id" on orders table → PK, not FK.
     */
    private boolean isForeignKey(String colName, String tableNameLower) {
        if (!colName.endsWith("_id") && !colName.endsWith("_fk")) return false;
        if (colName.equals("id")) return false;
        if (colName.equals(tableNameLower + "_id")) return false;
        return true;
    }

    private boolean isPrimaryKey(String colName, String tableNameLower) {
        return colName.equals("id")
                || colName.equals(tableNameLower + "_id")
                || colName.equals(tableNameLower + "_key")
                || colName.equals(tableNameLower + "_code");
    }

    /** "product_id" → "product",  "customer_fk" → "customer" */
    private String extractEntity(String colName) {
        if (colName.endsWith("_id"))  return colName.substring(0, colName.length() - 3);
        if (colName.endsWith("_fk"))  return colName.substring(0, colName.length() - 3);
        return null;
    }
}
