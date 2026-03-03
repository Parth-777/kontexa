package com.example.BACKEND.analytics.translator;

import java.util.HashMap;
import java.util.Map;

import com.example.BACKEND.analytics.cannonical.CanonicalEvent;
import com.example.BACKEND.analytics.dictionary.CanonicalField;
import com.example.BACKEND.analytics.dictionary.CanonicalFieldRegistry;
import com.example.BACKEND.analytics.vendor.VendorType;
import com.example.BACKEND.analytics.version.SchemaVersion;
import org.springframework.stereotype.Component;
@Component
public class EventTranslator {

    private final CanonicalFieldRegistry registry;

    public EventTranslator(CanonicalFieldRegistry registry) {
        this.registry = registry;
    }

    public CanonicalEvent translate(
            Map<String, Object> vendorPayload,
            VendorType vendorType,
            SchemaVersion schemaVersion
    ) {

        Map<String, Object> canonicalAttributes = new HashMap<>();

        for (CanonicalField field : registry.getFieldsForVersion(schemaVersion)) {
            String vendorKey = field.getVendorField(vendorType);
            if (vendorKey == null) continue;

            Object value = vendorPayload.get(vendorKey);
            if (value != null) {
                canonicalAttributes.put(field.getCanonicalName(), value);
            }
        }

        return new CanonicalEvent(canonicalAttributes, schemaVersion);
    }
}


