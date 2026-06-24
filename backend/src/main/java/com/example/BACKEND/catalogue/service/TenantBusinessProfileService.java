package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.example.BACKEND.catalogue.entity.TenantBusinessProfileEntity;
import com.example.BACKEND.catalogue.repository.TenantBusinessProfileRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tenant business context for executive narration (industry, north-star metrics, segments).
 */
@Service
public class TenantBusinessProfileService {

    private final TenantBusinessProfileRepository repository;
    private final ObjectMapper objectMapper;

    public TenantBusinessProfileService(
            TenantBusinessProfileRepository repository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public String promptBlock(String clientId) {
        return repository.findByClientId(clientId)
                .map(this::formatPrompt)
                .orElse("");
    }

    /**
     * Best-effort profile from catalogue at approval time.
     */
    @Transactional
    public void inferFromCatalogue(ClientCatalogueEntity catalogue) {
        String clientId = catalogue.getClientId();
        TenantBusinessProfileEntity profile = repository.findByClientId(clientId)
                .orElseGet(TenantBusinessProfileEntity::new);

        profile.setClientId(clientId);
        profile.setUpdatedAt(LocalDateTime.now());

        String schema = catalogue.getSchemaName() != null
                ? catalogue.getSchemaName().toLowerCase(Locale.ROOT) : "";
        profile.setIndustry(inferIndustry(schema));
        profile.setBusinessModel(inferBusinessModel(schema));

        List<String> metrics = new ArrayList<>();
        List<String> segments = new ArrayList<>();

        for (var table : catalogue.getTables()) {
            for (var col : table.getColumns()) {
                String name = col.getColumnName().toLowerCase(Locale.ROOT);
                String role = col.getRole() != null ? col.getRole().toLowerCase(Locale.ROOT) : "";
                if ("metric".equals(role) || isMetricName(name)) {
                    if (metrics.size() < 3) metrics.add(col.getColumnName());
                }
                if ("dimension".equals(role) || isSegmentName(name)) {
                    if (segments.size() < 3) segments.add(col.getColumnName());
                }
            }
        }

        try {
            profile.setNorthStarMetrics(objectMapper.writeValueAsString(metrics));
            profile.setPrimarySegments(objectMapper.writeValueAsString(segments));
        } catch (Exception e) {
            profile.setNorthStarMetrics("[]");
            profile.setPrimarySegments("[]");
        }

        repository.save(profile);
    }

    private String formatPrompt(TenantBusinessProfileEntity p) {
        StringBuilder sb = new StringBuilder("\nTENANT CONTEXT:\n");
        if (p.getIndustry() != null && !p.getIndustry().isBlank()) {
            sb.append("- Industry: ").append(p.getIndustry()).append("\n");
        }
        if (p.getBusinessModel() != null && !p.getBusinessModel().isBlank()) {
            sb.append("- Business model: ").append(p.getBusinessModel()).append("\n");
        }
        parseJsonArray(p.getNorthStarMetrics()).forEach(m ->
                sb.append("- North-star metric: ").append(m).append("\n"));
        parseJsonArray(p.getPrimarySegments()).forEach(s ->
                sb.append("- Key segment dimension: ").append(s).append("\n"));
        return sb.toString();
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = objectMapper.readTree(json);
            List<String> out = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode n : node) out.add(n.asText());
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String inferIndustry(String schema) {
        if (schema.contains("retail") || schema.contains("shop")) return "Retail / E-commerce";
        if (schema.contains("saas") || schema.contains("subscription")) return "B2B SaaS";
        if (schema.contains("logistics") || schema.contains("delivery")) return "Logistics";
        return "General";
    }

    private String inferBusinessModel(String schema) {
        if (schema.contains("marketplace")) return "Marketplace";
        if (schema.contains("saas")) return "B2B SaaS";
        if (schema.contains("retail")) return "Direct-to-consumer retail";
        return "General";
    }

    private boolean isMetricName(String name) {
        return name.contains("revenue") || name.contains("sales") || name.contains("amount")
                || name.contains("orders") || name.contains("mrr") || name.contains("count");
    }

    private boolean isSegmentName(String name) {
        return name.contains("region") || name.contains("category") || name.contains("segment")
                || name.contains("channel") || name.contains("product") || name.contains("country");
    }
}
