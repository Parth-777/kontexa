package com.example.BACKEND.catalogue.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a single column discovered from a client's database table.
 * Populated in three stages:
 *   Stage 1 (SchemaDiscoveryService)      → structure fields
 *   Stage 2 (DataSamplerService)          → sampleValues, min, max, avg
 *   Stage 3 (CatalogueEnrichmentService)  → description, synonyms, valueMeanings, role
 */
public class ColumnInfo {

    // --- Stage 1: structure (from information_schema) ---
    private String columnName;
    private String dataType;
    private boolean nullable;

    // --- Stage 2: sampled data ---
    private List<String> sampleValues = new ArrayList<>();
    private String minValue;
    private String maxValue;
    private String avgValue;
    private boolean skipped; // true if column was skipped during sampling (e.g. high-cardinality uuid)
    private String skipReason;

    // --- Stage 3: LLM enrichment ---
    // Plain English description of what this column represents
    private String description;
    // English words/phrases a non-technical user might say to refer to this column
    private List<String> synonyms = new ArrayList<>();
    // Meanings of coded values e.g. {"IN" -> "India", "pending" -> "order not yet processed"}
    private Map<String, String> valueMeanings = new HashMap<>();
    // Role of this column in queries: dimension | metric | filter | timestamp | identifier
    private String role;
    // Whether LLM enrichment ran on this column
    private boolean enriched = false;

    public ColumnInfo() {}

    public ColumnInfo(String columnName, String dataType, boolean nullable) {
        this.columnName = columnName;
        this.dataType = dataType;
        this.nullable = nullable;
    }

    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public boolean isNullable() { return nullable; }
    public void setNullable(boolean nullable) { this.nullable = nullable; }

    public List<String> getSampleValues() { return sampleValues; }
    public void setSampleValues(List<String> sampleValues) { this.sampleValues = sampleValues; }

    public String getMinValue() { return minValue; }
    public void setMinValue(String minValue) { this.minValue = minValue; }

    public String getMaxValue() { return maxValue; }
    public void setMaxValue(String maxValue) { this.maxValue = maxValue; }

    public String getAvgValue() { return avgValue; }
    public void setAvgValue(String avgValue) { this.avgValue = avgValue; }

    public boolean isSkipped() { return skipped; }
    public void setSkipped(boolean skipped) { this.skipped = skipped; }

    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getSynonyms() { return synonyms; }
    public void setSynonyms(List<String> synonyms) { this.synonyms = synonyms; }

    public Map<String, String> getValueMeanings() { return valueMeanings; }
    public void setValueMeanings(Map<String, String> valueMeanings) { this.valueMeanings = valueMeanings; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public boolean isEnriched() { return enriched; }
    public void setEnriched(boolean enriched) { this.enriched = enriched; }
}
