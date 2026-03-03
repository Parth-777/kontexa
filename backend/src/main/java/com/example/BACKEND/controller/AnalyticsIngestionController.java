package com.example.BACKEND.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.*;

import com.example.BACKEND.analytics.cannonical.CanonicalEvent;
import com.example.BACKEND.analytics.translator.EventTranslator;
import com.example.BACKEND.analytics.vendor.VendorType;
import com.example.BACKEND.analytics.version.SchemaVersion;


@RestController
@RequestMapping("/api/ingest")
@CrossOrigin(origins = "http://localhost:3000")
public class AnalyticsIngestionController {

    private final EventTranslator eventTranslator;

    // ✅ Spring injects this
    public AnalyticsIngestionController(EventTranslator eventTranslator) {
        this.eventTranslator = eventTranslator;
    }

    @PostMapping("/{vendor}")
    public CanonicalEvent ingestEvent(
            @PathVariable String vendor,
            @RequestParam(defaultValue = "V1") SchemaVersion schemaVersion,
            @RequestBody Map<String, Object> payload
    ) {
        VendorType vendorType = VendorType.valueOf(vendor.toUpperCase());

        return eventTranslator.translate(
                payload,
                vendorType,
                schemaVersion
        );
    }
}

