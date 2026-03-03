package com.example.BACKEND.analytics.version;

public enum SchemaVersion {
    V1,
    V2,
    V3;

    public boolean isBefore(SchemaVersion other) {
        return this.ordinal() < other.ordinal();
    }

    public boolean isAfterOrEqual(SchemaVersion other) {
        return this.ordinal() >= other.ordinal();
    }
}
