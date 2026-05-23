package com.example.BACKEND.catalogue.agent;

import java.util.List;
import java.util.Map;

/**
 * One labelled query result collected from a tenant's data source.
 * The label describes what was queried (e.g. "Trend: revenue over time").
 * Rows are fed as-is to the LLM synthesis prompt.
 */
public record CollectedData(String label, String sql, List<Map<String, Object>> rows) {}
