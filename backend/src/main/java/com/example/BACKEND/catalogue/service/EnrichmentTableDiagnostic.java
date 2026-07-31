package com.example.BACKEND.catalogue.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Forensic record for one table enrichment attempt.
 * Populated by instrumentation only — does not change enrichment behavior.
 */
public class EnrichmentTableDiagnostic {

    public String tableName;
    public int inputColumnCount;

    public boolean requestSent;
    public int systemPromptChars;
    public int userPromptChars;
    public int totalPromptChars;
    public int estimatedPromptTokens;
    public int maxTokensRequested;
    public String model;

    public boolean responseReceived;
    public int httpStatus;
    public int responseChars;
    public Integer promptTokens;
    public Integer completionTokens;
    public Integer totalTokens;
    public String finishReason;
    public boolean truncatedByLength;
    public boolean rawResponseRetained;
    public String rawResponsePath;
    public String responsePreview; // first ~500 chars
    public String errorMessage;

    public boolean jsonParseAttempted;
    public boolean jsonParseSucceeded;
    public String parseExceptionClass;
    public String parseExceptionMessage;
    public String parseStopLocation; // Jackson location or structural note

    public Integer columnsArraySize;
    public int mappedToColumnInfo;
    public int unknownColumnNames;
    public int withDescriptionAfter;
    public int withBusinessMeaningAfter;
    public int enrichedAfter;
    public List<String> sampleReturnedNames = new ArrayList<>();
    public List<String> sampleUnmappedNames = new ArrayList<>();
    public List<String> sampleMissingFromResponse = new ArrayList<>();

    public String completionStatus; // SUCCESS | HTTP_ERROR | EMPTY_RESPONSE | PARSE_FAIL | NO_COLUMNS_ARRAY | MAPPED_ZERO | MAPPED_PARTIAL | MAPPED_FULL

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tableName", tableName);
        m.put("inputColumnCount", inputColumnCount);
        m.put("requestSent", requestSent);
        m.put("systemPromptChars", systemPromptChars);
        m.put("userPromptChars", userPromptChars);
        m.put("totalPromptChars", totalPromptChars);
        m.put("estimatedPromptTokens", estimatedPromptTokens);
        m.put("maxTokensRequested", maxTokensRequested);
        m.put("model", model);
        m.put("responseReceived", responseReceived);
        m.put("httpStatus", httpStatus);
        m.put("responseChars", responseChars);
        m.put("promptTokens", promptTokens);
        m.put("completionTokens", completionTokens);
        m.put("totalTokens", totalTokens);
        m.put("finishReason", finishReason);
        m.put("truncatedByLength", truncatedByLength);
        m.put("rawResponseRetained", rawResponseRetained);
        m.put("rawResponsePath", rawResponsePath);
        m.put("responsePreview", responsePreview);
        m.put("errorMessage", errorMessage);
        m.put("jsonParseAttempted", jsonParseAttempted);
        m.put("jsonParseSucceeded", jsonParseSucceeded);
        m.put("parseExceptionClass", parseExceptionClass);
        m.put("parseExceptionMessage", parseExceptionMessage);
        m.put("parseStopLocation", parseStopLocation);
        m.put("columnsArraySize", columnsArraySize);
        m.put("mappedToColumnInfo", mappedToColumnInfo);
        m.put("unknownColumnNames", unknownColumnNames);
        m.put("withDescriptionAfter", withDescriptionAfter);
        m.put("withBusinessMeaningAfter", withBusinessMeaningAfter);
        m.put("enrichedAfter", enrichedAfter);
        m.put("sampleReturnedNames", sampleReturnedNames);
        m.put("sampleUnmappedNames", sampleUnmappedNames);
        m.put("sampleMissingFromResponse", sampleMissingFromResponse);
        m.put("completionStatus", completionStatus);
        return m;
    }
}
