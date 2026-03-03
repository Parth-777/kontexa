package com.example.BACKEND.catalogue.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "catalogue_columns")
public class CatalogueColumnEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_id", nullable = false)
    private CatalogueTableEntity catalogueTable;

    @Column(name = "column_name", nullable = false)
    private String columnName;

    @Column(name = "data_type")
    private String dataType;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** JSON array string: ["visits","views","seen_on"] */
    @Column(name = "synonyms", columnDefinition = "TEXT")
    private String synonyms;

    /** JSON object string: {"IN":"India","US":"United States"} */
    @Column(name = "value_meanings", columnDefinition = "TEXT")
    private String valueMeanings;

    /** JSON array string: ["page_view","purchase","button_click"] */
    @Column(name = "sample_values", columnDefinition = "TEXT")
    private String sampleValues;

    /** dimension | metric | filter | timestamp | identifier | freetext */
    @Column(name = "role")
    private String role;

    @Column(name = "min_value")
    private String minValue;

    @Column(name = "max_value")
    private String maxValue;

    @Column(name = "avg_value")
    private String avgValue;

    @Column(name = "is_sensitive", nullable = false)
    private boolean sensitive = false;

    @Column(name = "is_enriched", nullable = false)
    private boolean enriched = false;

    @Column(name = "is_skipped", nullable = false)
    private boolean skipped = false;

    @Column(name = "skip_reason")
    private String skipReason;

    // ── Getters / Setters ────────────────────────────────────────────

    public Long getId()                                  { return id; }
    public CatalogueTableEntity getCatalogueTable()      { return catalogueTable; }
    public void setCatalogueTable(CatalogueTableEntity t){ this.catalogueTable = t; }
    public String getColumnName()                        { return columnName; }
    public void   setColumnName(String s)                { this.columnName = s; }
    public String getDataType()                          { return dataType; }
    public void   setDataType(String s)                  { this.dataType = s; }
    public String getDescription()                       { return description; }
    public void   setDescription(String s)               { this.description = s; }
    public String getSynonyms()                          { return synonyms; }
    public void   setSynonyms(String s)                  { this.synonyms = s; }
    public String getValueMeanings()                     { return valueMeanings; }
    public void   setValueMeanings(String s)             { this.valueMeanings = s; }
    public String getSampleValues()                      { return sampleValues; }
    public void   setSampleValues(String s)              { this.sampleValues = s; }
    public String getRole()                              { return role; }
    public void   setRole(String s)                      { this.role = s; }
    public String getMinValue()                          { return minValue; }
    public void   setMinValue(String s)                  { this.minValue = s; }
    public String getMaxValue()                          { return maxValue; }
    public void   setMaxValue(String s)                  { this.maxValue = s; }
    public String getAvgValue()                          { return avgValue; }
    public void   setAvgValue(String s)                  { this.avgValue = s; }
    public boolean isSensitive()                         { return sensitive; }
    public void    setSensitive(boolean b)               { this.sensitive = b; }
    public boolean isEnriched()                          { return enriched; }
    public void    setEnriched(boolean b)                { this.enriched = b; }
    public boolean isSkipped()                           { return skipped; }
    public void    setSkipped(boolean b)                 { this.skipped = b; }
    public String  getSkipReason()                       { return skipReason; }
    public void    setSkipReason(String s)               { this.skipReason = s; }
}
