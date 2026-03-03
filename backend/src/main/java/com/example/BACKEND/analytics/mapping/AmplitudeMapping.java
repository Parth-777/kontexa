package com.example.BACKEND.analytics.mapping;


import com.example.BACKEND.analytics.vendor.VendorType;

import java.util.HashMap;
import java.util.Map;

public class AmplitudeMapping implements VendorFieldMapping {
    @Override
    public VendorType getVendor() {
        return VendorType.AMPLITUDE;
    }

    @Override
    public Map<String, String> fieldMap() {
        Map<String, String> map = new HashMap<>();

        map.put("eventName", "event_type");
        map.put("eventTime", "server_received_time");
        map.put("userId", "user_id");
        map.put("pageUrl", "page_url");
        map.put("browser", "browser");
        map.put("os", "os_name");

        return map;
    }
}
