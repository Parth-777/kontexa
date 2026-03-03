package com.example.BACKEND.analytics.mapping;


import com.example.BACKEND.analytics.vendor.VendorType;

import java.util.HashMap;
import java.util.Map;

public class MixPanelMapping  implements VendorFieldMapping {

    @Override
    public VendorType getVendor() {
        return VendorType.MIXPANEL;
    }

    @Override
    public Map<String, String> fieldMap() {
        Map<String, String> map = new HashMap<>();

        map.put("eventName", "event");
        map.put("eventTime", "time_processed");
        map.put("userId", "distinct_id");
        map.put("pageUrl", "current_url");
        map.put("browser", "browser");
        map.put("os", "os");

        return map;
    }

}
