package com.example.BACKEND.catalogue.charts;

import java.util.List;
import java.util.Map;

/**
 * Backend chart contract for the UI to render consistently.
 *
 * Chart rendering happens on the frontend; backend provides an intent-aligned spec.
 */
public class ChartSpec {

    public enum ChartType { BAR, HBAR, LINE, DONUT, HISTOGRAM, GROUPED_BAR, CORRELATION }

    private ChartType type;
    private String title;
    private String subtitle;

    /**
     * Primary series mapping.
     * - BAR/DONUT: categoryKey + valueKey
     * - LINE: xKey + yKey
     */
    private String categoryKey;
    private String valueKey;
    private String xKey;
    private String yKey;

    /** Suggested display formats (UI may choose to override). */
    private String valueFormat;   // currency|number|percent
    private String xFormat;       // date|category

    /** Chart data rows (already shaped for rendering). */
    private List<Map<String, Object>> data;

    /**
     * Cardinality metadata when chart data was simplified for readability.
     * The warehouse result set and statistics remain complete; only the visualization is reduced.
     */
    private int displayedRows;   // categories rendered individually
    private int totalRows;       // total grouped categories in the warehouse result
    private int aggregatedRows;  // categories folded into the "Other" bucket (0 when none)

    public ChartSpec() {}

    public ChartSpec(ChartType type, String title, String subtitle,
                     String categoryKey, String valueKey,
                     String xKey, String yKey,
                     String valueFormat, String xFormat,
                     List<Map<String, Object>> data) {
        this.type = type;
        this.title = title;
        this.subtitle = subtitle;
        this.categoryKey = categoryKey;
        this.valueKey = valueKey;
        this.xKey = xKey;
        this.yKey = yKey;
        this.valueFormat = valueFormat;
        this.xFormat = xFormat;
        this.data = data;
    }

    public ChartType getType() { return type; }
    public void setType(ChartType type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }

    public String getCategoryKey() { return categoryKey; }
    public void setCategoryKey(String categoryKey) { this.categoryKey = categoryKey; }
    public String getValueKey() { return valueKey; }
    public void setValueKey(String valueKey) { this.valueKey = valueKey; }
    public String getXKey() { return xKey; }
    public void setXKey(String xKey) { this.xKey = xKey; }
    public String getYKey() { return yKey; }
    public void setYKey(String yKey) { this.yKey = yKey; }

    public String getValueFormat() { return valueFormat; }
    public void setValueFormat(String valueFormat) { this.valueFormat = valueFormat; }
    public String getXFormat() { return xFormat; }
    public void setXFormat(String xFormat) { this.xFormat = xFormat; }

    public List<Map<String, Object>> getData() { return data; }
    public void setData(List<Map<String, Object>> data) { this.data = data; }

    public int getDisplayedRows() { return displayedRows; }
    public void setDisplayedRows(int displayedRows) { this.displayedRows = displayedRows; }
    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }
    public int getAggregatedRows() { return aggregatedRows; }
    public void setAggregatedRows(int aggregatedRows) { this.aggregatedRows = aggregatedRows; }
}

