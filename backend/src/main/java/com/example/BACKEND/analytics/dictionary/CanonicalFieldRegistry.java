package com.example.BACKEND.analytics.dictionary;

import com.example.BACKEND.analytics.dictionary.CanonicalField;
import com.example.BACKEND.analytics.vendor.VendorType;
import com.example.BACKEND.analytics.version.SchemaVersion;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CanonicalFieldRegistry {

    private final Map<String, CanonicalField> fieldMap = new HashMap<>();

    @PostConstruct
    public void init() {
        registerFields();
    }

    private void registerFields() {
        register(new CanonicalField(
                "eventName",
                CanonicalFieldCategory.EVENT_IDENTITY,
                "Name of the event",
                Map.of(
                        VendorType.MIXPANEL, "event",
                        VendorType.AMPLITUDE, "event_type"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "eventTime",
                CanonicalFieldCategory.EVENT_IDENTITY,
                "Client-side event timestamp",
                Map.of(
                        VendorType.MIXPANEL, "time",
                        VendorType.AMPLITUDE, "event_time"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "ingestedAt",
                CanonicalFieldCategory.EVENT_IDENTITY,
                "Server ingestion time",
                Map.of(
                        VendorType.MIXPANEL, "time_processed",
                        VendorType.AMPLITUDE, "server_upload_time"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "insertId",
                CanonicalFieldCategory.EVENT_IDENTITY,
                "De-duplication ID",
                Map.of(
                        VendorType.MIXPANEL, "insert_id",
                        VendorType.AMPLITUDE, "insert_id"
                ),
                SchemaVersion.V1,
                null
        ));


        // add rest here
        // ---------- 2. User Identity ----------

        register(new CanonicalField(
                "userId",
                CanonicalFieldCategory.USER_IDENTITY,
                "Authenticated user identifier",
                Map.of(
                        VendorType.MIXPANEL, "distinct_id",
                        VendorType.AMPLITUDE, "user_id"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "anonymousId",
                CanonicalFieldCategory.USER_IDENTITY,
                "Anonymous device identifier",
                Map.of(
                        VendorType.MIXPANEL, "$device_id",
                        VendorType.AMPLITUDE, "device_id"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "deviceId",
                CanonicalFieldCategory.USER_IDENTITY,
                "Physical device identifier",
                Map.of(
                        VendorType.MIXPANEL, "$device_id",
                        VendorType.AMPLITUDE, "device_id"
                ),
                SchemaVersion.V1,
                null
        ));

        // ---------- 3. Device & OS ----------

        register(new CanonicalField(
                "os",
                CanonicalFieldCategory.DEVICE_OS,
                "Operating system",
                Map.of(
                        VendorType.MIXPANEL, "$os",
                        VendorType.AMPLITUDE, "os_name"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "osVersion",
                CanonicalFieldCategory.DEVICE_OS,
                "Operating system version",
                Map.of(
                        VendorType.MIXPANEL, "$os_version",
                        VendorType.AMPLITUDE, "os_version"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "deviceModel",
                CanonicalFieldCategory.DEVICE_OS,
                "Device model",
                Map.of(
                        VendorType.MIXPANEL, "$device",
                        VendorType.AMPLITUDE, "device_model"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "deviceBrand",
                CanonicalFieldCategory.DEVICE_OS,
                "Hardware brand",
                Map.of(
                        VendorType.AMPLITUDE, "device_brand"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "deviceType",
                CanonicalFieldCategory.DEVICE_OS,
                "Mobile or Desktop device",
                Map.of(
                        VendorType.MIXPANEL, "$device_type",
                        VendorType.AMPLITUDE, "device_type"
                ),
                SchemaVersion.V1,
                null
        ));
        // ---------- 4. Browser & App Context ----------

        register(new CanonicalField(
                "browser",
                CanonicalFieldCategory.BROWSER_APP,
                "Browser name",
                Map.of(
                        VendorType.MIXPANEL, "$browser",
                        VendorType.AMPLITUDE, "browser"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "browserVersion",
                CanonicalFieldCategory.BROWSER_APP,
                "Browser version",
                Map.of(
                        VendorType.MIXPANEL, "$browser_version",
                        VendorType.AMPLITUDE, "browser_version"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "appVersion",
                CanonicalFieldCategory.BROWSER_APP,
                "Application version",
                Map.of(
                        VendorType.MIXPANEL, "$app_version",
                        VendorType.AMPLITUDE, "app_version"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "sdkVersion",
                CanonicalFieldCategory.BROWSER_APP,
                "SDK / library version",
                Map.of(
                        VendorType.MIXPANEL, "mp_lib_version",
                        VendorType.AMPLITUDE, "library"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "platform",
                CanonicalFieldCategory.BROWSER_APP,
                "Platform (Web / iOS / Android)",
                Map.of(
                        VendorType.MIXPANEL, "$platform",
                        VendorType.AMPLITUDE, "platform"
                ),
                SchemaVersion.V1,
                null
        ));
        // ---------- 5. Location & Network ----------

        register(new CanonicalField(
                "country",
                CanonicalFieldCategory.LOCATION_NETWORK,
                "Country",
                Map.of(
                        VendorType.MIXPANEL, "$country",
                        VendorType.AMPLITUDE, "country"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "region",
                CanonicalFieldCategory.LOCATION_NETWORK,
                "Region / State",
                Map.of(
                        VendorType.MIXPANEL, "$region",
                        VendorType.AMPLITUDE, "region"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "city",
                CanonicalFieldCategory.LOCATION_NETWORK,
                "City",
                Map.of(
                        VendorType.MIXPANEL, "$city",
                        VendorType.AMPLITUDE, "city"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "ip",
                CanonicalFieldCategory.LOCATION_NETWORK,
                "IP address",
                Map.of(
                        VendorType.MIXPANEL, "$ip",
                        VendorType.AMPLITUDE, "ip_address"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "language",
                CanonicalFieldCategory.LOCATION_NETWORK,
                "Language / locale",
                Map.of(
                        VendorType.MIXPANEL, "$language",
                        VendorType.AMPLITUDE, "language"
                ),
                SchemaVersion.V1,
                null
        ));
        // ---------- 6. Page / Screen Context ----------

        register(new CanonicalField(
                "pageUrl",
                CanonicalFieldCategory.PAGE_CONTEXT,
                "Page URL",
                Map.of(
                        VendorType.MIXPANEL, "$current_url",
                        VendorType.AMPLITUDE, "page_url"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "pageTitle",
                CanonicalFieldCategory.PAGE_CONTEXT,
                "Page title",
                Map.of(
                        VendorType.MIXPANEL, "$title",
                        VendorType.AMPLITUDE, "page_title"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "referrer",
                CanonicalFieldCategory.PAGE_CONTEXT,
                "Referrer URL",
                Map.of(
                        VendorType.MIXPANEL, "$referrer",
                        VendorType.AMPLITUDE, "referrer"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "screenName",
                CanonicalFieldCategory.PAGE_CONTEXT,
                "Screen name (mobile)",
                Map.of(
                        VendorType.AMPLITUDE, "screen_name"
                ),
                SchemaVersion.V1,
                null
        ));
// ---------- 7. Campaign & Attribution ----------

        register(new CanonicalField(
                "utmSource",
                CanonicalFieldCategory.CAMPAIGN_ATTRIBUTION,
                "UTM source",
                Map.of(
                        VendorType.MIXPANEL, "utm_source",
                        VendorType.AMPLITUDE, "utm_source"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "utmMedium",
                CanonicalFieldCategory.CAMPAIGN_ATTRIBUTION,
                "UTM medium",
                Map.of(
                        VendorType.MIXPANEL, "utm_medium",
                        VendorType.AMPLITUDE, "utm_medium"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "utmCampaign",
                CanonicalFieldCategory.CAMPAIGN_ATTRIBUTION,
                "UTM campaign",
                Map.of(
                        VendorType.MIXPANEL, "utm_campaign",
                        VendorType.AMPLITUDE, "utm_campaign"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "utmTerm",
                CanonicalFieldCategory.CAMPAIGN_ATTRIBUTION,
                "UTM term",
                Map.of(
                        VendorType.MIXPANEL, "utm_term",
                        VendorType.AMPLITUDE, "utm_term"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "utmContent",
                CanonicalFieldCategory.CAMPAIGN_ATTRIBUTION,
                "UTM content",
                Map.of(
                        VendorType.MIXPANEL, "utm_content",
                        VendorType.AMPLITUDE, "utm_content"
                ),
                SchemaVersion.V1,
                null
        ));
// ---------- 9. System & Debug Metadata ----------

        register(new CanonicalField(
                "processedAt",
                CanonicalFieldCategory.SYSTEM_METADATA,
                "Vendor processing time",
                Map.of(
                        VendorType.MIXPANEL, "time_processed",
                        VendorType.AMPLITUDE, "server_upload_time"
                ),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "schemaVersion",
                CanonicalFieldCategory.SYSTEM_METADATA,
                "Canonical schema version",
                Map.of(),
                SchemaVersion.V1,
                null
        ));

        register(new CanonicalField(
                "sampleRate",
                CanonicalFieldCategory.SYSTEM_METADATA,
                "Event sampling rate",
                Map.of(
                        VendorType.MIXPANEL, "sample_rate"
                ),
                SchemaVersion.V1,
                null
        ));


    }

    private void register(CanonicalField field) {
        fieldMap.put(field.getCanonicalName(), field);
    }

    public List<CanonicalField> getAll() {
        return new ArrayList<>(fieldMap.values());
    }

    public List<CanonicalField> search(String query) {
        String q = query.toLowerCase();
        return fieldMap.values().stream()
                .filter(f ->
                        f.getCanonicalName().toLowerCase().contains(q) ||
                                f.getDescription().toLowerCase().contains(q)
                )
                .toList();
    }

    public List<CanonicalField> getFieldsForVersion(SchemaVersion version) {
        return fieldMap.values().stream()
                .filter(f -> f.isSupportedIn(version))
                .toList();
    }
}
