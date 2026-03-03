package com.example.BACKEND.analytics.dictionary;

import com.example.BACKEND.analytics.vendor.VendorType;
import com.example.BACKEND.analytics.version.SchemaVersion;

import java.util.Map;

public class CanonicalField {

    private final String canonicalName;
    private final CanonicalFieldCategory category;
    private final String description;

    // Vendor → vendor field name
    private final Map<VendorType, String> vendorFieldMap;

    private final SchemaVersion introducedIn;
    private final SchemaVersion deprecatedIn;

    public CanonicalField(
            String canonicalName,
            CanonicalFieldCategory category,
            String description,
            Map<VendorType, String> vendorFieldMap,
            SchemaVersion introducedIn,
            SchemaVersion deprecatedIn
    ) {
        this.canonicalName = canonicalName;
        this.category = category;
        this.description = description;
        this.vendorFieldMap = vendorFieldMap;
        this.introducedIn = introducedIn;
        this.deprecatedIn = deprecatedIn;
    }

    // 🔹 THIS IS THE METHOD YOU ARE MISSING
    public String getVendorField(VendorType vendorType) {
        return vendorFieldMap.get(vendorType);
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public CanonicalFieldCategory getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }
    public SchemaVersion getIntroducedIn() {
        return introducedIn;
    }
    public SchemaVersion getDeprecatedIn() {
        return deprecatedIn;
    }
    public Map<VendorType, String> getVendorFieldMap() {
        return vendorFieldMap;
    }

    public boolean isSupportedIn(SchemaVersion version) {
        if (version.isBefore(introducedIn)) return false;
        if (deprecatedIn != null && !version.isBefore(deprecatedIn)) return false;
        return true;
    }
}
