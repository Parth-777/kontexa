package com.example.BACKEND.analytics.mapping;

import com.example.BACKEND.analytics.vendor.VendorType;

import java.util.Map;

public interface VendorFieldMapping {
    VendorType getVendor();
    Map<String, String> fieldMap();


}
