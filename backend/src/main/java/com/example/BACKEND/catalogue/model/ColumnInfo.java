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
 *   Stage 3 (CatalogueEnrichmentService)  → description, synonyms, valueMeanings, role,
 *                                           businessMeaning (rich semantic explanation)
 */
public class ColumnInfo {

    // --- Stage 1: structure (from information_schema) ---
    private String columnName;
    private String dataType;
    private boolean nullable;
    /** True for RECORD/STRUCT/OBJECT parents — not bindable as dimensions. */
    private boolean container;
    /** Parent path for nested leaves, e.g. trafficSource for trafficSource.source */
    private String parentPath;
    /** Warehouse field mode: NULLABLE, REQUIRED, REPEATED, etc. */
    private String fieldMode;

    // --- Stage 2: sampled data ---
    private List<String> sampleValues = new ArrayList<>();
    private String minValue;
    private String maxValue;
    private String avgValue;
    private boolean skipped; // true if column was skipped during sampling (e.g. high-cardinality uuid)
    private String skipReason;

    // --- Stage 3: LLM enrichment ---
    /** Short human-readable summary for admin UI / quick scan. */
    private String description;
    /**
     * Primary semantic knowledge for future GPT/Claude reasoning.
     * Rich analyst-style explanation (what it stores, business meaning, when to use,
     * aggregation, grain, caveats). Single source of truth for field meaning.
     */
    private String businessMeaning;
    /** Optional weak hints only (prefer ≤3); never an exhaustive synonym dictionary. */
    private List<String> synonyms = new ArrayList<>();
    // Meanings of coded values e.g. {"IN" -> "India", "pending" -> "order not yet processed"}
    private Map<String, String> valueMeanings = new HashMap<>();
    // Role of this column in queries: dimension | metric | filter | timestamp | identifier
    private String role;
    /** Optional generate-time aggregation hint; approve-time enricher may also set this. */
    private String aggregationMethod;
    /** 0–1 confidence in the semantic enrichment; null if unknown. */
    private Double confidence;
    /**
     * Machine-readable Insights display unit: currency | percent | count | number | raw.
     * Optional; Insights fails closed for currency formatting when absent/unscaled.
     */
    private String displayUnit;
    /**
     * Multiply raw warehouse value by this to obtain canonical business units.
     * Null means scale is unknown.
     */
    private Double scaleToCanonical;
    /** Conservative entity label for COUNT(*) language (sessions, rows, records). */
    private String grainLabel;
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

    public boolean isContainer() { return container; }
    public void setContainer(boolean container) { this.container = container; }

    public String getParentPath() { return parentPath; }
    public void setParentPath(String parentPath) { this.parentPath = parentPath; }

    public String getFieldMode() { return fieldMode; }
    public void setFieldMode(String fieldMode) { this.fieldMode = fieldMode; }

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

    public String getBusinessMeaning() { return businessMeaning; }
    public void setBusinessMeaning(String businessMeaning) { this.businessMeaning = businessMeaning; }

    public List<String> getSynonyms() { return synonyms; }
    public void setSynonyms(List<String> synonyms) { this.synonyms = synonyms; }

    public Map<String, String> getValueMeanings() { return valueMeanings; }
    public void setValueMeanings(Map<String, String> valueMeanings) { this.valueMeanings = valueMeanings; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getAggregationMethod() { return aggregationMethod; }
    public void setAggregationMethod(String aggregationMethod) { this.aggregationMethod = aggregationMethod; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getDisplayUnit() { return displayUnit; }
    public void setDisplayUnit(String displayUnit) { this.displayUnit = displayUnit; }

    public Double getScaleToCanonical() { return scaleToCanonical; }
    public void setScaleToCanonical(Double scaleToCanonical) { this.scaleToCanonical = scaleToCanonical; }

    public String getGrainLabel() { return grainLabel; }
    public void setGrainLabel(String grainLabel) { this.grainLabel = grainLabel; }

    public boolean isEnriched() { return enriched; }
    public void setEnriched(boolean enriched) { this.enriched = enriched; }
}
