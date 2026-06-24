package com.example.BACKEND.onboarding;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/onboarding")
public class OnboardingLeadController {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    private final OnboardingLeadService leadService;

    public OnboardingLeadController(OnboardingLeadService leadService) {
        this.leadService = leadService;
    }

    @PostMapping("/leads")
    public ResponseEntity<?> submitLead(@RequestBody Map<String, String> body) {
        String fullName     = trim(body.get("fullName"));
        String workEmail    = trim(body.get("workEmail"));
        String companyName  = trim(body.get("companyName"));
        String companySize  = trim(body.get("companySize"));
        String dataWarehouse = trim(body.get("dataWarehouse"));
        String useCase      = trim(body.get("useCase"));
        String sourcePage   = trim(body.get("sourcePage"));

        if (fullName == null || workEmail == null || companyName == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "fullName, workEmail, and companyName are required"
            ));
        }

        if (!EMAIL_PATTERN.matcher(workEmail).matches()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Please enter a valid work email address"
            ));
        }

        try {
            long id = leadService.createLead(new OnboardingLeadService.OnboardingLeadRequest(
                    fullName, workEmail, companyName,
                    companySize, dataWarehouse, useCase,
                    sourcePage != null ? sourcePage : "homepage"
            ));
            return ResponseEntity.status(HttpStatus.CREATED).body(leadService.toResponse(id));
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "Failed to save lead";
            if (msg.contains("onboarding_leads")) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                        "error", "Onboarding is not configured yet. Run onboarding_leads_migration.sql in pgAdmin."
                ));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to submit request"));
        }
    }

    private String trim(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
