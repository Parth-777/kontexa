package com.example.BACKEND.controller;

import com.example.BACKEND.analytics.dictionary.CanonicalField;
import com.example.BACKEND.analytics.dictionary.CanonicalFieldRegistry;
import com.example.BACKEND.analytics.version.SchemaVersion;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/schema")
@CrossOrigin(origins = "http://localhost:3000")
public class SchemaController {

    private final CanonicalFieldRegistry registry;

    public SchemaController(CanonicalFieldRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/all")
    public List<CanonicalField> getAllFields(
            @RequestParam(defaultValue = "V1") SchemaVersion version
    ) {
        return registry.getFieldsForVersion(version);
    }

    @GetMapping("/search")
    public List<CanonicalField> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "V1") SchemaVersion version
    ) {
        return registry.getFieldsForVersion(version).stream()
                .filter(f ->
                        f.getCanonicalName().toLowerCase().contains(query.toLowerCase()) ||
                                f.getDescription().toLowerCase().contains(query.toLowerCase())
                )
                .toList();
    }
}
