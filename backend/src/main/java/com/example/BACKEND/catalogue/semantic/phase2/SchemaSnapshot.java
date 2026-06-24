package com.example.BACKEND.catalogue.semantic.phase2;

import java.util.List;

public record SchemaSnapshot(
        String tableRef,
        List<SchemaColumn> columns
) {
    public record SchemaColumn(String name, String type) {}
}
