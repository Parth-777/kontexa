package com.example.BACKEND.analytics.cannonical;

import java.time.Instant;
import java.util.Map;

import com.example.BACKEND.analytics.version.SchemaVersion;

public class CanonicalEvent {

    private final Map<String, Object> attributes;
    private final SchemaVersion schemaVersion;

    public CanonicalEvent(Map<String, Object> attributes,
                          SchemaVersion schemaVersion) {
        this.attributes = attributes;
        this.schemaVersion = schemaVersion;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public SchemaVersion getSchemaVersion() {
        return schemaVersion;
    }
}

