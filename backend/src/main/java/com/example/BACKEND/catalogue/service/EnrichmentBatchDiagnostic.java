package com.example.BACKEND.catalogue.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Diagnostics for one leaf enrichment batch attempt (including retries). */
public class EnrichmentBatchDiagnostic {

    public int batchNumber;
    public int attempt;
    public int leafCount;
    public List<String> leafNames = new ArrayList<>();
    public int estimatedPromptTokens;
    public int estimatedCompletionTokens;
    public boolean requestSent;
    public int httpStatus;
    public Integer promptTokens;
    public Integer completionTokens;
    public Integer totalTokens;
    public String finishReason;
    public boolean truncatedByLength;
    public int retryCount;
    public boolean jsonParseSucceeded;
    public String parseExceptionMessage;
    public String parseStopLocation;
    public Integer columnsArraySize;
    public int mappedCount;
    public String status; // SUCCESS | RETRY | FAILED_LENGTH | FAILED_PARSE | FAILED_MAPPING | FAILED_HTTP | SPLIT
    public String errorMessage;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchNumber", batchNumber);
        m.put("attempt", attempt);
        m.put("leafCount", leafCount);
        m.put("leafNames", leafNames);
        m.put("estimatedPromptTokens", estimatedPromptTokens);
        m.put("estimatedCompletionTokens", estimatedCompletionTokens);
        m.put("requestSent", requestSent);
        m.put("httpStatus", httpStatus);
        m.put("promptTokens", promptTokens);
        m.put("completionTokens", completionTokens);
        m.put("totalTokens", totalTokens);
        m.put("finishReason", finishReason);
        m.put("truncatedByLength", truncatedByLength);
        m.put("retryCount", retryCount);
        m.put("jsonParseSucceeded", jsonParseSucceeded);
        m.put("parseExceptionMessage", parseExceptionMessage);
        m.put("parseStopLocation", parseStopLocation);
        m.put("columnsArraySize", columnsArraySize);
        m.put("mappedCount", mappedCount);
        m.put("status", status);
        m.put("errorMessage", errorMessage);
        return m;
    }
}
