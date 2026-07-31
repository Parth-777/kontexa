package com.example.BACKEND.catalogue.schema;

import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Recursively flattens nested warehouse schema fields into bindable leaf paths.
 */
public final class NestedSchemaDiscoverer {

    private NestedSchemaDiscoverer() {
    }

    public record DiscoveredField(
            String fieldPath,
            String dataType,
            boolean nullable,
            String mode,
            boolean container
    ) {}

    public static void discoverBigQueryFields(FieldList fields, Consumer<DiscoveredField> consumer) {
        if (fields == null) {
            return;
        }
        discoverBigQueryFields(fields, "", consumer);
    }

    private static void discoverBigQueryFields(
            FieldList fields,
            String prefix,
            Consumer<DiscoveredField> consumer
    ) {
        for (Field field : fields) {
            if (field == null) {
                continue;
            }
            String name = field.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            String path = NestedSchemaFieldGraph.joinPath(prefix, name);
            String type = field.getType() == null ? "" : field.getType().name().toLowerCase(Locale.ROOT);
            String mode = field.getMode() == null ? "NULLABLE" : field.getMode().name();
            boolean nullable = !"REQUIRED".equalsIgnoreCase(mode);
            FieldList subFields = field.getSubFields();
            boolean hasSubFields = subFields != null && !subFields.isEmpty();
            boolean recordLike = "record".equals(type) || hasSubFields;

            if (recordLike && hasSubFields) {
                consumer.accept(new DiscoveredField(path, type, nullable, mode, true));
                discoverBigQueryFields(subFields, path, consumer);
                continue;
            }

            if ("repeated".equalsIgnoreCase(mode) && hasSubFields) {
                consumer.accept(new DiscoveredField(path, type, nullable, mode, true));
                discoverBigQueryFields(subFields, path, consumer);
                continue;
            }

            consumer.accept(new DiscoveredField(path, type, nullable, mode, false));
        }
    }

    public static List<DiscoveredField> discoverSnowflakeDescribeRows(List<SnowflakeDescribeRow> rows) {
        List<DiscoveredField> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (SnowflakeDescribeRow row : rows) {
            if (row.name() == null || row.name().isBlank()) {
                continue;
            }
            String path = NestedSchemaFieldGraph.normalizePath(row.name());
            String type = row.type() == null ? "" : row.type().toLowerCase(Locale.ROOT);
            boolean container = NestedSchemaFieldGraph.isNestedContainerType(type);
            out.add(new DiscoveredField(path, type, true, row.kind(), container));
        }
        return out;
    }

    public record SnowflakeDescribeRow(String name, String type, String kind) {}
}
