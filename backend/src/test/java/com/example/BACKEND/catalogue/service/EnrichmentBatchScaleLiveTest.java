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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live proof that #24-scale (336 nested columns) enriches via token-budget batches
 * without truncated-JSON salvage. Skips without OpenAI key / DB.
 */
class EnrichmentBatchScaleLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    @DisplayName("Batch enrichment scales to catalogue #24 leaf fields")
    void enrichCatalogue24WithBatches() throws Exception {
        String apiKey = prop("OPENAI_API_KEY", "openai.api.key");
        String model = prop("OPENAI_MODEL", "openai.model");
        assumeTrue(usableKey(apiKey), "No OpenAI key");
        assumeTrue(canConnectDb(), "admindb unavailable");
        if (model == null || model.isBlank()) model = "gpt-4o-mini";

        CatalogueResult catalogue = loadCatalogueFromDb(24);
        assumeTrue(catalogue.getTotalColumns() >= 100, "expected large catalogue #24");

        OpenAiClient client = new OpenAiClient(
                apiKey, model, MAPPER, new ModelCapabilityRegistry(), new OpenAiRequestFactory(MAPPER));
        CatalogueEnrichmentService enricher = new CatalogueEnrichmentService(client, new PromptBuilder());

        enricher.enrich(catalogue);

        List<EnrichmentBatchDiagnostic> diags = enricher.getLastBatchDiagnostics();
        long successes = diags.stream().filter(d -> "SUCCESS".equals(d.status)).count();
        long lengths = diags.stream().filter(d -> "FAILED_LENGTH".equals(d.status)).count();

        int withBm = 0;
        int leaves = 0;
        int containers = 0;
        for (TableInfo t : catalogue.getTables()) {
            for (ColumnInfo c : t.getColumns()) {
                boolean container = c.isContainer()
                        || NestedSchemaFieldGraph.isNestedContainerType(c.getDataType());
                if (container) {
                    containers++;
                    assertTrue(c.isEnriched(), "container should have local metadata: " + c.getColumnName());
                } else {
                    leaves++;
                    if (c.getBusinessMeaning() != null && !c.getBusinessMeaning().isBlank()) {
                        withBm++;
                    }
                }
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("totalColumns", catalogue.getTotalColumns());
        report.put("leaves", leaves);
        report.put("containers", containers);
        report.put("leavesWithBusinessMeaning", withBm);
        report.put("batchAttempts", diags.size());
        report.put("batchSuccesses", successes);
        report.put("batchLengthFailures", lengths);
        report.put("batches", diags.stream().map(EnrichmentBatchDiagnostic::toMap).toList());

        Path out = Path.of("target/enrichment-diagnostics/batch-scale-24-report.json");
        Files.createDirectories(out.getParent());
        MAPPER.writeValue(out.toFile(), report);
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Report: " + out.toAbsolutePath());

        assertTrue(successes >= 1, "expected at least one successful batch");
        // Majority of leaves should get meaning; allow some stubborn failures
        assertTrue(withBm >= (int) (leaves * 0.8),
                "expected >=80% leaves with businessMeaning, got " + withBm + "/" + leaves);
        // No accepted truncated success path: length failures must not be marked SUCCESS
        assertTrue(diags.stream().noneMatch(d ->
                "SUCCESS".equals(d.status) && d.truncatedByLength));
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
        try (InputStream in = EnrichmentBatchScaleLiveTest.class.getClassLoader()
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
}
