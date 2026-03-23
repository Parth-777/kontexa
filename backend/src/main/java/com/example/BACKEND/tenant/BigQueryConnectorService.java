package com.example.BACKEND.tenant;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableResult;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BigQueryConnectorService {

    public record BigQueryTestResult(String connectionLink, String projectId, String location, String dataset) {}

    public BigQueryTestResult testConnection(
            String projectId,
            String serviceAccountJson,
            String location,
            String dataset
    ) {
        String normalizedProject = require(projectId, "projectId");
        String normalizedCreds = require(serviceAccountJson, "serviceAccountJson");
        String normalizedLocation = normalize(location);
        String normalizedDataset = normalize(dataset);

        GoogleCredentials credentials = parseCredentials(normalizedCreds);
        BigQueryOptions.Builder optionsBuilder = BigQueryOptions.newBuilder()
                .setProjectId(normalizedProject)
                .setCredentials(credentials);
        if (normalizedLocation != null) {
            optionsBuilder.setLocation(normalizedLocation);
        }

        BigQuery bigQuery = optionsBuilder.build().getService();
        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder("SELECT 1 AS ok")
                .setUseLegacySql(false)
                .build();

        try {
            TableResult result = bigQuery.query(queryConfig);
            Iterable<FieldValueList> rows = result.iterateAll();
            if (!rows.iterator().hasNext()) {
                throw new RuntimeException("BigQuery connection opened but test query returned no rows.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("BigQuery test query interrupted", ex);
        } catch (BigQueryException ex) {
            throw new RuntimeException("BigQuery connection failed: " + ex.getMessage(), ex);
        }

        String link = buildConnectionLink(normalizedProject, normalizedLocation, normalizedDataset);
        return new BigQueryTestResult(
                link,
                normalizedProject,
                normalizedLocation == null ? "" : normalizedLocation,
                normalizedDataset == null ? "" : normalizedDataset
        );
    }

    public String buildConnectionLink(String projectId, String location, String dataset) {
        StringBuilder sb = new StringBuilder("bigquery://").append(projectId.trim());
        boolean hasParam = false;
        if (location != null && !location.isBlank()) {
            sb.append(hasParam ? "&" : "?");
            sb.append("location=").append(location.trim());
            hasParam = true;
        }
        if (dataset != null && !dataset.isBlank()) {
            sb.append(hasParam ? "&" : "?");
            sb.append("dataset=").append(dataset.trim());
        }
        return sb.toString();
    }

    public List<String> listTables(
            String projectId,
            String serviceAccountJson,
            String location,
            String dataset
    ) {
        String normalizedProject = require(projectId, "projectId");
        String normalizedCreds = require(serviceAccountJson, "serviceAccountJson");
        String normalizedDataset = require(dataset, "dataset");
        String normalizedLocation = normalize(location);

        BigQuery bigQuery = createClient(normalizedProject, normalizedCreds, normalizedLocation);
        DatasetId datasetId = DatasetId.of(normalizedProject, normalizedDataset);
        List<String> tables = new ArrayList<>();

        try {
            for (Table table : bigQuery.listTables(datasetId).iterateAll()) {
                if (table != null && table.getTableId() != null && table.getTableId().getTable() != null) {
                    tables.add(table.getTableId().getTable());
                }
            }
            return tables;
        } catch (BigQueryException ex) {
            throw new RuntimeException("Failed to list BigQuery tables: " + ex.getMessage(), ex);
        }
    }

    public List<Map<String, Object>> executeSelect(
            String projectId,
            String serviceAccountJson,
            String location,
            String dataset,
            String sql
    ) {
        String normalizedProject = require(projectId, "projectId");
        String normalizedCreds = require(serviceAccountJson, "serviceAccountJson");
        String normalizedSql = require(sql, "sql");
        String normalizedLocation = normalize(location);
        String normalizedDataset = normalize(dataset);

        BigQuery bigQuery = createClient(normalizedProject, normalizedCreds, normalizedLocation);
        QueryJobConfiguration.Builder builder = QueryJobConfiguration.newBuilder(normalizedSql)
                .setUseLegacySql(false);
        if (normalizedDataset != null) {
            builder.setDefaultDataset(DatasetId.of(normalizedProject, normalizedDataset));
        }
        QueryJobConfiguration queryConfig = builder.build();

        try {
            TableResult result = bigQuery.query(queryConfig);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (FieldValueList row : result.iterateAll()) {
                Map<String, Object> mapped = new LinkedHashMap<>();
                if (result.getSchema() != null && result.getSchema().getFields() != null) {
                    for (int i = 0; i < result.getSchema().getFields().size(); i++) {
                        String col = result.getSchema().getFields().get(i).getName();
                        mapped.put(col, row.get(i).isNull() ? null : row.get(i).getValue());
                    }
                }
                rows.add(mapped);
            }
            return rows;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("BigQuery query interrupted", ex);
        } catch (BigQueryException ex) {
            throw new RuntimeException("BigQuery execution failed: " + ex.getMessage(), ex);
        }
    }

    public com.google.cloud.bigquery.Table getTable(
            String projectId,
            String serviceAccountJson,
            String location,
            String dataset,
            String tableName
    ) {
        BigQuery bigQuery = createClient(require(projectId, "projectId"), require(serviceAccountJson, "serviceAccountJson"), normalize(location));
        return bigQuery.getTable(TableId.of(projectId, require(dataset, "dataset"), require(tableName, "tableName")));
    }

    private BigQuery createClient(String projectId, String serviceAccountJson, String location) {
        GoogleCredentials credentials = parseCredentials(serviceAccountJson);
        BigQueryOptions.Builder optionsBuilder = BigQueryOptions.newBuilder()
                .setProjectId(projectId)
                .setCredentials(credentials);
        if (location != null) {
            optionsBuilder.setLocation(location);
        }
        return optionsBuilder.build().getService();
    }

    private GoogleCredentials parseCredentials(String serviceAccountPayload) {
        String payload = serviceAccountPayload.trim();
        String json = payload;

        if (!payload.startsWith("{")) {
            try {
                byte[] decoded = Base64.getDecoder().decode(payload);
                json = new String(decoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("serviceAccountJson must be raw JSON or base64 JSON", ex);
            }
        }

        try {
            GoogleCredentials creds = ServiceAccountCredentials.fromStream(
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))
            );
            return creds.createScoped("https://www.googleapis.com/auth/cloud-platform");
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid serviceAccountJson content", ex);
        }
    }

    private String require(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
