package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.llm.ModelCapabilityRegistry;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.example.BACKEND.catalogue.llm.OpenAiRequestFactory;
import com.example.BACKEND.catalogue.llm.PromptBuilder;
import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.model.ColumnInfo;
import com.example.BACKEND.catalogue.model.TableInfo;
import com.example.BACKEND.catalogue.schema.NestedSchemaFieldGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Updated forensics after batching: #22 remains a small single/few-batch success;
 * #24 must succeed via multiple token-budget batches (no truncated salvage).
 */
class EnrichmentFailureForensicsLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    @DisplayName("Forensics: #22 vs #24 under batched enrichment")
    void compareCatalogue22Vs24Enrichment() throws Exception {
        String apiKey = prop("OPENAI_API_KEY", "openai.api.key");
        String model = prop("OPENAI_MODEL", "openai.model");
        assumeTrue(usableKey(apiKey), "No OpenAI key — skip");
        if (model == null || model.isBlank()) model = "gpt-4o-mini";
        assumeTrue(canConnectDb(), "admindb not reachable — skip");

        CatalogueResult cat22 = loadCatalogueFromDb(22);
        CatalogueResult cat24 = loadCatalogueFromDb(24);
        assumeTrue(cat22.getTotalColumns() > 0, "catalogue 22 missing");
        assumeTrue(cat24.getTotalColumns() > 0, "catalogue 24 missing");

        PromptBuilder promptBuilder = new PromptBuilder();
        OpenAiClient client = new OpenAiClient(
                apiKey, model, MAPPER, new ModelCapabilityRegistry(), new OpenAiRequestFactory(MAPPER));
        CatalogueEnrichmentService enricher = new CatalogueEnrichmentService(client, promptBuilder);

        enricher.enrich(cat22);
        List<EnrichmentBatchDiagnostic> diag22 = enricher.getLastBatchDiagnostics();
        enricher.enrich(cat24);
        List<EnrichmentBatchDiagnostic> diag24 = enricher.getLastBatchDiagnostics();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("catalogue22", Map.of(
                "columns", cat22.getTotalColumns(),
                "businessMeaningCount", countBm(cat22),
                "batches", diag22.stream().map(EnrichmentBatchDiagnostic::toMap).toList()
        ));
        report.put("catalogue24", Map.of(
                "columns", cat24.getTotalColumns(),
                "businessMeaningCount", countBm(cat24),
                "batches", diag24.stream().map(EnrichmentBatchDiagnostic::toMap).toList()
        ));
        report.put("compare", Map.of(
                "columns_22", cat22.getTotalColumns(),
                "columns_24", cat24.getTotalColumns(),
                "bm_22", countBm(cat22),
                "bm_24", countBm(cat24),
                "batchSuccesses_22", diag22.stream().filter(d -> "SUCCESS".equals(d.status)).count(),
                "batchSuccesses_24", diag24.stream().filter(d -> "SUCCESS".equals(d.status)).count(),
                "lengthRejects_24", diag24.stream().filter(d -> "FAILED_LENGTH".equals(d.status)).count()
        ));

        Path out = Path.of("target/enrichment-diagnostics/forensics-22-vs-24-batched.json");
        Files.createDirectories(out.getParent());
        MAPPER.writeValue(out.toFile(), report);
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report.get("compare")));
        System.out.println("Wrote " + out.toAbsolutePath());

        assumeTrue(countBm(cat24) > 50, "batched #24 should enrich many leaves");
    }

    private static CatalogueResult loadCatalogueFromDb(long catalogueId) throws Exception {
        Properties p = loadProps();
        CatalogueResult result = new CatalogueResult();
        try (Connection conn = DriverManager.getConnection(
                p.getProperty("spring.datasource.url"),
                p.getProperty("spring.datasource.username"),
                p.getProperty("spring.datasource.password"))) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT database_name, schema_name FROM client_catalogues WHERE id=?")) {
                ps.setLong(1, catalogueId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return result;
                    result.setDatabaseName(rs.getString(1));
                    result.setSchemaName(rs.getString(2));
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, table_name, table_schema, row_count FROM catalogue_tables WHERE catalogue_id=? ORDER BY id")) {
                ps.setLong(1, catalogueId);
                try (ResultSet trs = ps.executeQuery()) {
                    while (trs.next()) {
                        long tableId = trs.getLong(1);
                        TableInfo table = new TableInfo(trs.getString(2), trs.getString(3));
                        table.setRowCount(trs.getLong(4));
                        try (PreparedStatement cps = conn.prepareStatement(
                                "SELECT column_name, data_type, role, sample_values, min_value, max_value, avg_value FROM catalogue_columns WHERE table_id=? ORDER BY id")) {
                            cps.setLong(1, tableId);
                            try (ResultSet crs = cps.executeQuery()) {
                                while (crs.next()) {
                                    String name = crs.getString(1);
                                    String type = crs.getString(2);
                                    String role = crs.getString(3);
                                    ColumnInfo col = new ColumnInfo(name, type, true);
                                    boolean container = NestedSchemaFieldGraph.ROLE_CONTAINER.equalsIgnoreCase(role)
                                            || NestedSchemaFieldGraph.isNestedContainerType(type);
                                    col.setContainer(container);
                                    col.setParentPath(NestedSchemaFieldGraph.parentPath(name));
                                    col.setSampleValues(parseSamples(crs.getString(4)));
                                    col.setMinValue(crs.getString(5));
                                    col.setMaxValue(crs.getString(6));
                                    col.setAvgValue(crs.getString(7));
                                    table.addColumn(col);
                                }
                            }
                        }
                        result.addTable(table);
                    }
                }
            }
        }
        return result;
    }

    private static List<String> parseSamples(String raw) {
        if (raw == null || raw.isBlank() || "[]".equals(raw.trim())) return new ArrayList<>();
        try {
            return MAPPER.readValue(raw, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static boolean canConnectDb() {
        try {
            Properties p = loadProps();
            try (Connection conn = DriverManager.getConnection(
                    p.getProperty("spring.datasource.url"),
                    p.getProperty("spring.datasource.username"),
                    p.getProperty("spring.datasource.password"))) {
                return conn.isValid(3);
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static Properties loadProps() throws Exception {
        try (InputStream in = EnrichmentFailureForensicsLiveTest.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            Properties props = new Properties();
            props.load(in);
            return props;
        }
    }

    private static String prop(String envKey, String propKey) {
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv.trim();
        try {
            return loadProps().getProperty(propKey);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean usableKey(String apiKey) {
        return apiKey != null && apiKey.startsWith("sk-") && apiKey.length() > 20
                && !apiKey.contains("YOUR") && !apiKey.contains("placeholder");
    }

    private static int countBm(CatalogueResult c) {
        int n = 0;
        for (TableInfo t : c.getTables()) {
            for (ColumnInfo col : t.getColumns()) {
                if (col.getBusinessMeaning() != null && !col.getBusinessMeaning().isBlank()) n++;
            }
        }
        return n;
    }
}
