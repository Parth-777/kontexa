package com.example.BACKEND.experiment.phase1;

import java.util.List;

/**
 * Physical schema snapshot — table + column types only.
 */
public record Phase1SchemaSnapshot(
        String tableRef,
        List<Phase1SchemaColumn> columns
) {
    public record Phase1SchemaColumn(String name, String type) {}
}
