package com.example.BACKEND.catalogue.llm;

import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.model.ColumnInfo;
import com.example.BACKEND.catalogue.model.TableInfo;
import com.example.BACKEND.catalogue.service.CatalogueEnrichmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Step 2 semantic quality review: runs generate-time enrichment with the new
 * concept-first prompt against a realistic GA Sessions–style physical schema
 * (column names + sample values mirror Kontexa warehouse shapes).
 *
 * Skipped when no OpenAI API key is configured.
 * Writes {@code target/semantic-catalogue-step2-review.json} for human review.
 * Does not touch Phase 1/2 runtime, approval, or snapshot loaders.
 */
class SemanticCatalogueStep2GenerationReviewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    @DisplayName("Step 2: generate rich businessMeaning catalogue for review")
    void generateSemanticCatalogueForReview() throws Exception {
        String apiKey = resolveProperty("OPENAI_API_KEY", "openai.api.key");
        String model = resolveProperty("OPENAI_MODEL", "openai.model");
        assumeTrue(isUsableKey(apiKey), "No usable OpenAI API key — skipping Step 2 review generation");
        if (model == null || model.isBlank()) {
            model = "gpt-4o-mini";
        }

        OpenAiClient client = new OpenAiClient(
                apiKey,
                model,
                MAPPER,
                new ModelCapabilityRegistry(),
                new OpenAiRequestFactory(MAPPER)
        );
        CatalogueEnrichmentService enricher = new CatalogueEnrichmentService(client, new PromptBuilder());

        CatalogueResult catalogue = sampleGaSessionsCatalogue();
        enricher.enrich(catalogue);

        List<Map<String, Object>> reviewRows = new ArrayList<>();
        for (TableInfo table : catalogue.getTables()) {
            for (ColumnInfo col : table.getColumns()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("field", col.getColumnName());
                row.put("dataType", col.getDataType());
                row.put("role", col.getRole());
                row.put("aggregationMethod", col.getAggregationMethod());
                row.put("confidence", col.getConfidence());
                row.put("description", col.getDescription());
                row.put("businessMeaning", col.getBusinessMeaning());
                row.put("synonyms", col.getSynonyms());
                row.put("valueMeanings", col.getValueMeanings());
                row.put("businessMeaningChars",
                        col.getBusinessMeaning() == null ? 0 : col.getBusinessMeaning().length());
                reviewRows.add(row);
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("businessProfile", catalogue.getBusinessProfile());
        report.put("entities", catalogue.getEntities());
        report.put("globalCaveats", catalogue.getGlobalCaveats());
        report.put("fields", reviewRows);

        Path out = Path.of("target/semantic-catalogue-step2-review.json");
        Files.createDirectories(out.getParent());
        MAPPER.writeValue(out.toFile(), report);

        System.out.println("=== Step 2 semantic catalogue review ===");
        System.out.println("businessProfile: " + catalogue.getBusinessProfile());
        System.out.println("Wrote: " + out.toAbsolutePath());
        for (Map<String, Object> row : reviewRows) {
            System.out.println("\n--- " + row.get("field") + " [" + row.get("role") + "] ---");
            System.out.println("description: " + row.get("description"));
            System.out.println("businessMeaning:\n" + row.get("businessMeaning"));
            System.out.println("synonyms: " + row.get("synonyms")
                    + " | agg=" + row.get("aggregationMethod")
                    + " | confidence=" + row.get("confidence"));
        }

        long withMeaning = reviewRows.stream()
                .filter(r -> r.get("businessMeaning") != null
                        && !r.get("businessMeaning").toString().isBlank())
                .count();
        assertTrue(withMeaning >= 10, "expected >=10 fields with businessMeaning, got " + withMeaning);

        // Quality guard: businessMeaning should be substantially richer than description
        long rich = reviewRows.stream()
                .filter(r -> {
                    Object bm = r.get("businessMeaning");
                    Object d = r.get("description");
                    if (bm == null || bm.toString().isBlank()) return false;
                    int bmLen = bm.toString().length();
                    int dLen = d == null ? 0 : d.toString().length();
                    return bmLen >= 180 && bmLen > dLen * 2;
                })
                .count();
        assertTrue(rich >= 8, "expected >=8 richly explained fields, got " + rich);

        for (Map<String, Object> row : reviewRows) {
            @SuppressWarnings("unchecked")
            List<String> synonyms = (List<String>) row.get("synonyms");
            if (synonyms != null) {
                assertTrue(synonyms.size() <= 3,
                        row.get("field") + " has too many synonyms: " + synonyms.size());
            }
        }
    }

    /**
     * Realistic subset of Google Analytics Sessions–style nested fields
     * (names/samples mirror Kontexa GA warehouse shapes used in Phase 2 work).
     */
    private static CatalogueResult sampleGaSessionsCatalogue() {
        CatalogueResult result = new CatalogueResult("bigquery-public-data", "google_analytics_sample");
        TableInfo table = new TableInfo("ga_sessions_20160801", "google_analytics_sample");
        table.setRowCount(1_000);

        table.addColumn(col("visitId", "INTEGER", false, null,
                List.of("1470046245", "1470046402", "1470047120"), "1470046245", "1470120000", null));
        table.addColumn(col("fullVisitorId", "STRING", false, null,
                List.of("389283974", "719238401", "102938475"), null, null, null));
        table.addColumn(col("date", "STRING", false, null,
                List.of("20160801"), "20160801", "20160801", null));
        table.addColumn(col("totals.visits", "INTEGER", false, "totals",
                List.of("1"), "0", "1", "0.98"));
        table.addColumn(col("totals.hits", "INTEGER", false, "totals",
                List.of("1", "3", "12", "28"), "1", "120", "4.2"));
        table.addColumn(col("totals.pageviews", "INTEGER", false, "totals",
                List.of("1", "2", "5", "15"), "1", "80", "3.1"));
        table.addColumn(col("totals.bounces", "INTEGER", false, "totals",
                List.of("1", "null"), "0", "1", null));
        table.addColumn(col("totals.transactionRevenue", "INTEGER", false, "totals",
                List.of("0", "19990000", "45990000"), "0", "200000000", "850000"));
        table.addColumn(col("trafficSource.source", "STRING", false, "trafficSource",
                List.of("(direct)", "google", "youtube.com", "facebook.com"), null, null, null));
        table.addColumn(col("trafficSource.medium", "STRING", false, "trafficSource",
                List.of("(none)", "organic", "referral", "cpc"), null, null, null));
        table.addColumn(col("device.deviceCategory", "STRING", false, "device",
                List.of("desktop", "mobile", "tablet"), null, null, null));
        table.addColumn(col("geoNetwork.country", "STRING", false, "geoNetwork",
                List.of("United States", "India", "United Kingdom", "Canada"), null, null, null));
        table.addColumn(col("geoNetwork.city", "STRING", false, "geoNetwork",
                List.of("New York", "Bengaluru", "London", "not available in demo dataset"), null, null, null));
        table.addColumn(col("channelGrouping", "STRING", false, null,
                List.of("Organic Search", "Direct", "Social", "Referral", "Paid Search"), null, null, null));
        table.addColumn(col("totals", "RECORD", true, null, List.of(), null, null, null));

        result.addTable(table);
        return result;
    }

    private static ColumnInfo col(
            String name,
            String type,
            boolean container,
            String parentPath,
            List<String> samples,
            String min,
            String max,
            String avg
    ) {
        ColumnInfo c = new ColumnInfo(name, type, true);
        c.setContainer(container);
        c.setParentPath(parentPath);
        c.setSampleValues(new ArrayList<>(samples));
        c.setMinValue(min);
        c.setMaxValue(max);
        c.setAvgValue(avg);
        return c;
    }

    private static String resolveProperty(String envKey, String propKey) {
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromSysProp = System.getProperty(propKey);
        if (fromSysProp != null && !fromSysProp.isBlank()) {
            return fromSysProp.trim();
        }
        try (InputStream in = SemanticCatalogueStep2GenerationReviewTest.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in == null) return null;
            Properties props = new Properties();
            props.load(in);
            String value = props.getProperty(propKey);
            return value != null ? value.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isUsableKey(String apiKey) {
        return apiKey != null
                && apiKey.startsWith("sk-")
                && apiKey.length() > 20
                && !apiKey.contains("YOUR")
                && !apiKey.contains("placeholder");
    }
}
