package com.example.BACKEND.catalogue.schema;

import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot.CatalogueColumn;

import java.util.Locale;

/**
 * Path-aware field graph helpers for nested warehouse schemas.
 * Generic — no dataset-specific rules.
 */
public final class NestedSchemaFieldGraph {

    public static final String ROLE_CONTAINER = "container";

    private NestedSchemaFieldGraph() {
    }

    public static boolean isNestedContainerType(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return false;
        }
        String upper = dataType.toUpperCase(Locale.ROOT);
        return upper.contains("RECORD")
                || upper.contains("STRUCT")
                || upper.contains("OBJECT")
                || upper.equals("VARIANT")
                || upper.equals("JSON")
                || upper.equals("ARRAY");
    }

    public static boolean isContainer(CatalogueColumn column) {
        if (column == null) {
            return false;
        }
        if (column.container()) {
            return true;
        }
        if (ROLE_CONTAINER.equalsIgnoreCase(column.role())) {
            return true;
        }
        return isNestedContainerType(column.dataType());
    }

    public static boolean isBindableLeaf(CatalogueColumn column) {
        return column != null && !isContainer(column);
    }

    public static String parentPath(String fieldPath) {
        if (fieldPath == null || fieldPath.isBlank()) {
            return null;
        }
        int dot = fieldPath.lastIndexOf('.');
        return dot > 0 ? fieldPath.substring(0, dot) : null;
    }

    public static String joinPath(String prefix, String name) {
        if (name == null || name.isBlank()) {
            return prefix;
        }
        if (prefix == null || prefix.isBlank()) {
            return name;
        }
        return prefix + "." + name;
    }

    public static String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        return path.replace(':', '.').trim();
    }
}
