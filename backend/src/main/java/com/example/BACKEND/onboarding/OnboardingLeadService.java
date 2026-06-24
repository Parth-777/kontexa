package com.example.BACKEND.onboarding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class OnboardingLeadService {

    private final JdbcTemplate jdbc;

    public OnboardingLeadService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long createLead(OnboardingLeadRequest request) {
        String domain = extractDomain(request.workEmail());

        return jdbc.queryForObject("""
                INSERT INTO onboarding_leads
                    (full_name, work_email, company_name, company_domain,
                     company_size, data_warehouse, use_case, source_page)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                request.fullName().trim(),
                request.workEmail().trim().toLowerCase(),
                request.companyName().trim(),
                domain,
                blankToNull(request.companySize()),
                blankToNull(request.dataWarehouse()),
                blankToNull(request.useCase()),
                request.sourcePage() != null ? request.sourcePage().trim() : "homepage"
        );
    }

    static String extractDomain(String email) {
        if (email == null || !email.contains("@")) return null;
        String domain = email.substring(email.indexOf('@') + 1).trim().toLowerCase();
        return domain.isEmpty() ? null : domain;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    public record OnboardingLeadRequest(
            String fullName,
            String workEmail,
            String companyName,
            String companySize,
            String dataWarehouse,
            String useCase,
            String sourcePage
    ) {}

    public Map<String, Object> toResponse(long id) {
        return Map.of(
                "id", id,
                "status", "received",
                "message", "Thanks. Our team will contact you shortly to provision your Kontexa workspace."
        );
    }
}
