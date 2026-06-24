package com.example.BACKEND.tenant;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.JobStatistics;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

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
            throw new RuntimeException(formatConnectionError(ex), ex);
        }

        String link = buildConnectionLink(normalizedProject, normalizedLocation, normalizedDataset);
        return new BigQueryTestResult(
                link,
                normalizedProject,
                normalizedLocation == null ? "" : normalizedLocation,
                normalizedDataset == null ? "" : normalizedDataset
        );
    }

    private String formatConnectionError(BigQueryException ex) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        if (msg.contains("Invalid JWT Signature") || msg.contains("invalid_grant")) {
            return "BigQuery authentication failed: invalid or expired service account key. "
                    + "In GCP go to IAM → Service Accounts → Keys, create a NEW JSON key, "
                    + "and paste the complete file (must include private_key and client_email).";
        }
        return "BigQuery connection failed: " + msg;
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

    /**
     * Dry-run to estimate bytes processed (for scale guard).
     */
    public long estimateQueryBytes(
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
                .setDryRun(true)
                .setUseLegacySql(false);
        if (normalizedDataset != null) {
            builder.setDefaultDataset(DatasetId.of(normalizedProject, normalizedDataset));
        }

        try {
            JobId jobId = JobId.newBuilder().setRandomJob().build();
            Job job = bigQuery.create(JobInfo.newBuilder(builder.build()).setJobId(jobId).build());
            JobStatistics stats = job.getStatistics();
            if (stats instanceof JobStatistics.QueryStatistics qs && qs.getTotalBytesProcessed() != null) {
                return qs.getTotalBytesProcessed();
            }
            return 0L;
        } catch (BigQueryException ex) {
            System.out.printf("[BigQuery] Dry-run failed: %s%n", ex.getMessage());
            return 0L;
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

    /**
     * Validates that the pasted content is a complete GCP service-account key file.
     */
    public void validateServiceAccountJson(String serviceAccountPayload) {
        String json = normalizeServiceAccountJson(serviceAccountPayload);
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!"service_account".equals(node.path("type").asText())) {
                throw new IllegalArgumentException(
                        "JSON must be a GCP service account key (type: service_account). "
                                + "Download a new key from IAM → Service Accounts → Keys.");
            }
            if (node.path("private_key").asText("").isBlank()) {
                throw new IllegalArgumentException(
                        "Service account JSON is incomplete — missing private_key. "
                                + "Paste the ENTIRE downloaded .json file (starts with {\"type\":\"service_account\"...), "
                                + "not just the last few lines.");
            }
            if (!node.path("private_key").asText("").contains("BEGIN PRIVATE KEY")) {
                throw new IllegalArgumentException(
                        "private_key looks truncated or corrupted. Download a fresh JSON key from GCP.");
            }
            if (node.path("client_email").asText("").isBlank()) {
                throw new IllegalArgumentException("Service account JSON is missing client_email.");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Service account JSON is not valid JSON. Paste the full file from GCP.", ex);
        }
    }

    private String normalizeServiceAccountJson(String serviceAccountPayload) {
        String payload = serviceAccountPayload.trim();
        if (!payload.startsWith("{")) {
            try {
                byte[] decoded = Base64.getDecoder().decode(payload);
                return new String(decoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("serviceAccountJson must be raw JSON or base64 JSON", ex);
            }
        }
        return payload;
    }

    private GoogleCredentials parseCredentials(String serviceAccountPayload) {
        String json = normalizeServiceAccountJson(serviceAccountPayload);
        validateServiceAccountJson(json);

        try {
            GoogleCredentials creds = ServiceAccountCredentials.fromStream(
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))
            );
            return creds.createScoped("https://www.googleapis.com/auth/cloud-platform");
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid serviceAccountJson content: " + ex.getMessage(), ex);
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
