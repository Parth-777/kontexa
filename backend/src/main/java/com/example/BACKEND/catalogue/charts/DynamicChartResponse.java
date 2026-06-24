package com.example.BACKEND.catalogue.charts;

/** Response for POST /api/agent/charts/generate */
public class DynamicChartResponse {

    private boolean success;
    private String answer;
    private ChartSpec chart;
    private String generatedSql;
    private String error;

    public DynamicChartResponse() {}

    public DynamicChartResponse(boolean success, String answer, ChartSpec chart,
                                String generatedSql, String error) {
        this.success = success;
        this.answer = answer;
        this.chart = chart;
        this.generatedSql = generatedSql;
        this.error = error;
    }

    public static DynamicChartResponse from(DynamicChartService.DynamicChartResult result) {
        return new DynamicChartResponse(
                result.success(),
                result.answer(),
                result.chart(),
                result.generatedSql(),
                result.error()
        );
    }

    public boolean isSuccess() { return success; }
    public boolean getSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public ChartSpec getChart() { return chart; }
    public void setChart(ChartSpec chart) { this.chart = chart; }
    public String getGeneratedSql() { return generatedSql; }
    public void setGeneratedSql(String generatedSql) { this.generatedSql = generatedSql; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
