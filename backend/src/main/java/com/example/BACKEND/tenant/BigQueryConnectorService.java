package com.example.BACKEND.tenant;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
