package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.decision.api.DecisionResponseMapper;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionExecutionContext;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import com.example.BACKEND.catalogue.decision.runtime.DecisionRuntime;
import com.example.BACKEND.identity.auth.AuthContext;
import com.example.BACKEND.identity.auth.AuthContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Live Phase 2 failure-path dig for GA sessions ranking questions.
 * Enable with: -Dkontexa.live.ga.trace=true
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "kontexa.live.ga.trace", matches = "true")
class GaSessionsCountryPhase2FailTraceLiveTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final String Q_WORKS =
            "Which traffic sources generate the most sessions?";
    private static final String Q_FAILS =
            "Which countries generate the most sessions?";

    @Autowired DecisionRuntime decisionRuntime;
    @Autowired DecisionResponseMapper responseMapper;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        AuthContextHolder.clear();
        Phase2InvestigationPathTrace.clear();
    }

    @Test
    @Transactional
    @DisplayName("Trace traffic-source (works) vs country (fails) Phase 2 path")
    void compareTrafficVsCountrySessions() throws Exception {
        String forced = System.getProperty("kontexa.live.ga.tenant");
        System.out.println("forced tenant prop=" + forced);
        TenantHit tenant;
        if (forced != null && !forced.isBlank()) {
            UUID ws = findWorkspaceId(forced).orElse(UUID.randomUUID());
            tenant = new TenantHit(ws, forced.trim(), "forced");
            System.out.println("Forced tenant=" + tenant.slug() + " workspaceId=" + tenant.workspaceId()
                    + " (workspace row present=" + findWorkspaceId(forced).isPresent() + ")");
        } else {
            tenant = discoverGaTenant();
        }
        if (tenant == null) {
            System.out.println("No GA-like workspace with APPROVED catalogue found — cannot live-trace.");
            return;
        }
        System.out.println("Using tenant slug=" + tenant.slug() + " workspaceId=" + tenant.workspaceId()
                + " via=" + tenant.how());

        AuthContextHolder.set(new AuthContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                tenant.workspaceId(),
                tenant.slug(),
                "phase2-trace@local",
                "Phase2 Trace",
                "ADMIN",
                "test"));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("tenant", tenant.slug());
        report.put("works_question", Q_WORKS);
        report.put("fails_question", Q_FAILS);
        report.put("works", runOne(Q_WORKS, tenant.slug()));
        report.put("fails", runOne(Q_FAILS, tenant.slug()));

        Path out = Path.of("target", "phase2-investigation-traces",
                "ga-traffic-vs-country-sessions.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, JSON.writeValueAsString(report));
        System.out.println("Wrote " + out.toAbsolutePath());
        System.out.println(JSON.writeValueAsString(report.get("fails")));
    }

    private Map<String, Object> runOne(String question, String tenantSlug) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("question", question);
        Phase2InvestigationPathTrace.clear();
        try {
            DecisionExecutionContext ctx = DecisionRuntime.contextFrom(tenantSlug, question, Map.of());
            row.put("run_id", ctx.runId().toString());
            DecisionRunResult result = decisionRuntime.execute(ctx);
            Map<String, Object> response = responseMapper.toRunResponse(ctx.runId(), result, Map.of());
            row.put("status", "OK");
            row.put("served_path", response.get("served_path"));
            row.put("planner_execution_mode", response.get("planner_execution_mode"));
            row.put("execution_path", response.get("execution_path"));
            if (response.get("investigation") != null) {
                row.put("investigation", response.get("investigation"));
            }
        } catch (Exception ex) {
            row.put("status", "ERROR");
            row.put("error_class", ex.getClass().getName());
            row.put("error_message", ex.getMessage());
            Throwable root = ex;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            row.put("root_cause_class", root.getClass().getName());
            row.put("root_cause_message", root.getMessage());
            StringBuilder stack = new StringBuilder();
            for (StackTraceElement el : root.getStackTrace()) {
                stack.append(el).append("\n");
                if (stack.length() > 6000) {
                    break;
                }
            }
            row.put("root_cause_stack_head", stack.toString());
        }
        Phase2InvestigationPathTrace t = Phase2InvestigationPathTrace.current();
        if (t != null) {
            row.put("phase2_trace", t.snapshot());
            t.writeToFile((question.contains("countries") ? "FAIL_" : "OK_") + question);
        }
        return row;
    }

    private TenantHit discoverGaTenant() {
        List<String> approved = List.of();
        try {
            approved = jdbc.query(
                    "SELECT DISTINCT client_id FROM client_catalogues WHERE UPPER(status) = 'APPROVED'",
                    (rs, i) -> rs.getString(1));
            System.out.println("APPROVED catalogue tenants: " + approved);
        } catch (Exception e) {
            System.out.println("list approved failed: " + e.getMessage());
        }

        // Prefer approved tenants with GA-ish column names in related catalogue tables.
        for (String slug : approved) {
            if (catalogueMentionsGa(slug)) {
                Optional<TenantHit> hit = resolveWorkspace(slug);
                if (hit.isPresent()) {
                    return new TenantHit(hit.get().workspaceId(), hit.get().slug(), "approved_ga_fields");
                }
                // client_id may itself be the tenant slug even without workspaces row match
                Optional<UUID> ws = findWorkspaceId(slug);
                if (ws.isPresent()) {
                    return new TenantHit(ws.get(), slug, "approved_ga_fields");
                }
            }
        }

        // Any approved tenant — try each until first succeeds registry resolution later
        for (String slug : approved) {
            Optional<UUID> ws = findWorkspaceId(slug);
            if (ws.isPresent()) {
                return new TenantHit(ws.get(), slug, "any_approved");
            }
            // Invent a workspace UUID when auth only needs slug match for TenantAccessGuard
            // but DecisionRuntime may still need real workspace — skip if no workspace.
        }

        // Name heuristics
        try {
            List<TenantHit> named = new ArrayList<>();
            jdbc.query(
                    "SELECT id, slug, name FROM workspaces",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        String slug = rs.getString("slug");
                        String name = rs.getString("name");
                        String blob = ((slug != null ? slug : "") + " " + (name != null ? name : ""))
                                .toLowerCase();
                        if (blob.contains("analytic") || blob.contains("ga")
                                || blob.contains("google")) {
                            named.add(new TenantHit(
                                    UUID.fromString(rs.getString("id")),
                                    slug,
                                    "workspace_name"));
                        }
                    });
            for (TenantHit h : named) {
                Integer n = countApproved(h.slug());
                if (n != null && n > 0) {
                    return h;
                }
            }
            if (!named.isEmpty()) {
                return named.getFirst();
            }
        } catch (Exception e) {
            System.out.println("workspace scan failed: " + e.getMessage());
        }
        return null;
    }

    private boolean catalogueMentionsGa(String clientId) {
        String[] sqls = {
                "SELECT COUNT(*) FROM catalogue_columns cc "
                        + "JOIN catalogue_tables ct ON ct.id = cc.table_id "
                        + "JOIN client_catalogues c ON c.id = ct.catalogue_id "
                        + "WHERE c.client_id = ? AND UPPER(c.status)='APPROVED' AND ("
                        + "cc.column_name ILIKE '%geoNetwork%' "
                        + "OR cc.column_name ILIKE '%trafficSource%' "
                        + "OR cc.column_name ILIKE '%totals.visits%' "
                        + "OR cc.column_name ILIKE 'country' "
                        + "OR LOWER(cc.column_name) LIKE '%.country' "
                        + "OR cc.description ILIKE '%google analytics%')",
                "SELECT COUNT(*) FROM catalogue_tables ct "
                        + "JOIN client_catalogues c ON c.id = ct.catalogue_id "
                        + "WHERE c.client_id = ? AND UPPER(c.status)='APPROVED' AND ("
                        + "ct.table_name ILIKE '%ga_sessions%' "
                        + "OR ct.table_name ILIKE '%analytics%sample%' "
                        + "OR ct.fully_qualified_name ILIKE '%google_analytics%')"
        };
        for (String sql : sqls) {
            try {
                Integer n = jdbc.queryForObject(sql, Integer.class, clientId);
                if (n != null && n > 0) {
                    System.out.println("GA marker hit for client=" + clientId + " count=" + n);
                    return true;
                }
            } catch (Exception e) {
                System.out.println("GA probe sql failed for " + clientId + ": " + e.getMessage());
            }
        }
        return false;
    }

    private Integer countApproved(String clientId) {
        try {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM client_catalogues WHERE client_id = ? AND UPPER(status)='APPROVED'",
                    Integer.class,
                    clientId);
        } catch (Exception e) {
            return 0;
        }
    }

    private Optional<UUID> findWorkspaceId(String slug) {
        try {
            List<UUID> ids = jdbc.query(
                    "SELECT id FROM workspaces WHERE slug = ? LIMIT 1",
                    (rs, i) -> UUID.fromString(rs.getString(1)),
                    slug);
            return ids.stream().findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<TenantHit> resolveWorkspace(String slug) {
        return findWorkspaceId(slug).map(id -> new TenantHit(id, slug, "forced_or_approved"));
    }

    private record TenantHit(UUID workspaceId, String slug, String how) {}
}
