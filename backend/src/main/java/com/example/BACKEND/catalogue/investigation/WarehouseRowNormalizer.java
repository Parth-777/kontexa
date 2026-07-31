package com.example.BACKEND.catalogue.investigation;

import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts warehouse connector cell values into JSON-safe Java primitives/maps/lists.
 *
 * <p>Prevents BigQuery {@link FieldValue} / {@link FieldValueList} leakage into
 * diagnostic facts, EvidencePack rows, and HTTP responses.
 */
public final class WarehouseRowNormalizer {

    private WarehouseRowNormalizer() {
    }

    public static List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(normalizeRow(row));
        }
        return out;
    }

    public static Map<String, Object> normalizeRow(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            out.put(entry.getKey(), normalizeValue(entry.getValue()));
        }
        return out;
    }

    public static Object normalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof FieldValue fieldValue) {
            return normalizeFieldValue(fieldValue);
        }
        if (value instanceof FieldValueList fieldValueList) {
            return normalizeFieldValueList(fieldValueList);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), normalizeValue(entry.getValue()));
            }
            return out;
        }
        if (value instanceof Iterable<?> iterable
                && !(value instanceof String)
                && !(value instanceof byte[])) {
            List<Object> out = new ArrayList<>();
            for (Object item : iterable) {
                out.add(normalizeValue(item));
            }
            return out;
        }
        if (isJsonSafePrimitive(value)) {
            return value;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof BigInteger integer) {
            return integer.longValue();
        }
        if (value instanceof TemporalAccessor temporal) {
            return temporal.toString();
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        String className = value.getClass().getName();
        if (className.startsWith("com.google.cloud.bigquery.")) {
            return String.valueOf(value);
        }
        return String.valueOf(value);
    }

    public static Object normalizeFieldValue(FieldValue fieldValue) {
        if (fieldValue == null || fieldValue.isNull()) {
            return null;
        }
        return switch (fieldValue.getAttribute()) {
            case PRIMITIVE -> normalizePrimitive(fieldValue);
            case RECORD -> normalizeFieldValueList(fieldValue.getRecordValue());
            case REPEATED -> {
                List<Object> items = new ArrayList<>();
                for (FieldValue item : fieldValue.getRepeatedValue()) {
                    items.add(normalizeFieldValue(item));
                }
                yield items;
            }
            default -> String.valueOf(fieldValue);
        };
    }

    private static Object normalizeFieldValueList(FieldValueList values) {
        if (values == null) {
            return null;
        }
        List<Object> items = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            items.add(normalizeFieldValue(values.get(i)));
        }
        return items;
    }

    private static Object normalizePrimitive(FieldValue fieldValue) {
        try {
            return fieldValue.getStringValue();
        } catch (IllegalStateException ignored) {
            // fall through
        }
        try {
            return fieldValue.getBooleanValue();
        } catch (IllegalStateException ignored) {
            // fall through
        }
        try {
            return fieldValue.getLongValue();
        } catch (IllegalStateException ignored) {
            // fall through
        }
        try {
            return fieldValue.getDoubleValue();
        } catch (IllegalStateException ignored) {
            // fall through
        }
        try {
            return fieldValue.getTimestampInstant().toString();
        } catch (IllegalStateException ignored) {
            // fall through
        }
        try {
            byte[] bytes = fieldValue.getBytesValue();
            return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
        } catch (IllegalStateException ignored) {
            // fall through
        }
        return null;
    }

    private static boolean isJsonSafePrimitive(Object value) {
        return value instanceof String
                || value instanceof Boolean
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof Short
                || value instanceof Byte
                || value instanceof Character;
    }
}
