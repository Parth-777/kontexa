package com.example.BACKEND.catalogue.investigation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates EvidencePack request results against raw SQL row shapes.
 * Does not re-execute SQL — checks that returned rows are consistent with
 * each EvidenceRequest.kind and that values are extractable for narrative/charts.
 */
@Component
public class EvidencePackValidator {

    public record Issue(
            String requestId,
            String kind,
            String severity,
            String code,
            String message
    ) {}

    public record ValidationReport(
            boolean ok,
            int requestsChecked,
            int issuesFound,
            List<Issue> issues,
            List<Map<String, Object>> requestTraces
    ) {}

    public ValidationReport validate(EvidencePack pack) {
        List<Issue> issues = new ArrayList<>();
        List<Map<String, Object>> traces = new ArrayList<>();
        if (pack == null || pack.requestResults() == null) {
            return new ValidationReport(false, 0, 1,
                    List.of(new Issue(null, null, "ERROR", "PACK_NULL", "EvidencePack or requestResults is null")),
                    List.of());
        }

        for (EvidencePack.RequestResult rr : pack.requestResults()) {
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("request_id", rr.requestId());
            trace.put("kind", rr.kind());
            trace.put("role", rr.role());
            trace.put("ok", rr.ok());
            trace.put("row_count", rr.rows() != null ? rr.rows().size() : 0);
            trace.put("has_sql", rr.sql() != null && !rr.sql().isBlank());

            if (!rr.ok()) {
                issues.add(new Issue(rr.requestId(), rr.kind(), "WARN", "REQUEST_FAILED",
                        rr.error() != null ? rr.error() : "request marked not ok"));
                trace.put("status", "FAILED");
                traces.add(trace);
                continue;
            }

            List<Issue> kindIssues = validateKind(rr);
            issues.addAll(kindIssues);
            trace.put("status", kindIssues.isEmpty() ? "VALID" : "INVALID");
            trace.put("issues", kindIssues.stream().map(Issue::code).toList());
            if (rr.rows() != null && !rr.rows().isEmpty()) {
                trace.put("sample_row_keys", new ArrayList<>(rr.rows().getFirst().keySet()));
                trace.put("sample_row", rr.rows().getFirst());
            }
            if (rr.sql() != null) {
                trace.put("sql_preview", rr.sql().length() > 240 ? rr.sql().substring(0, 240) + "…" : rr.sql());
                validateSqlMentions(rr, issues, trace);
            }
            traces.add(trace);
        }

        boolean ok = issues.stream().noneMatch(i -> "ERROR".equals(i.severity()));
        return new ValidationReport(ok, pack.requestResults().size(), issues.size(),
                List.copyOf(issues), List.copyOf(traces));
    }

    public Map<String, Object> toMap(ValidationReport report) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", report.ok());
        out.put("requests_checked", report.requestsChecked());
        out.put("issues_found", report.issuesFound());
        out.put("issues", report.issues().stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("request_id", i.requestId());
            m.put("kind", i.kind());
            m.put("severity", i.severity());
            m.put("code", i.code());
            m.put("message", i.message());
            return m;
        }).toList());
        out.put("request_traces", report.requestTraces());
        return out;
    }

    private List<Issue> validateKind(EvidencePack.RequestResult rr) {
        List<Issue> issues = new ArrayList<>();
        String kind = rr.kind() == null ? "" : rr.kind().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = rr.rows();
        if (rows == null || rows.isEmpty()) {
            issues.add(new Issue(rr.requestId(), rr.kind(), "ERROR", "EMPTY_ROWS",
                    "Succeeded request returned zero rows"));
            return issues;
        }
        Map<String, Object> first = rows.getFirst();
        Set<String> keys = first.keySet();

        switch (kind) {
            case "slice_metric" -> requireAny(rr, keys, issues, "value");
            case "correlation" -> {
                requireAny(rr, keys, issues, "correlation");
                Object corr = first.get("correlation");
                if (corr instanceof Number n) {
                    double v = n.doubleValue();
                    if (v < -1.0001 || v > 1.0001) {
                        issues.add(new Issue(rr.requestId(), rr.kind(), "ERROR", "CORR_OUT_OF_RANGE",
                                "correlation=" + v + " outside [-1,1]"));
                    }
                }
            }
            case "time_series" -> {
                requireAny(rr, keys, issues, "period", "value");
                requireAny(rr, keys, issues, "value");
            }
            case "rank", "top_n", "bottom_n", "share", "metric_by_dimension" ->
                    requireAny(rr, keys, issues, "member");
            case "concentration" -> {
                requireAny(rr, keys, issues, "member");
                requireAny(rr, keys, issues, "share", "cumulative_share", "value");
            }
            case "distribution" -> requireAny(rr, keys, issues, "member", "count");
            default -> issues.add(new Issue(rr.requestId(), rr.kind(), "WARN", "UNKNOWN_KIND_SHAPE",
                    "No row-shape validator for kind=" + rr.kind()));
        }
        return issues;
    }

    private void requireAny(
            EvidencePack.RequestResult rr,
            Set<String> keys,
            List<Issue> issues,
            String... required
    ) {
        for (String key : required) {
            if (keys.contains(key)) {
                return;
            }
        }
        issues.add(new Issue(rr.requestId(), rr.kind(), "ERROR", "MISSING_COLUMNS",
                "Expected one of " + String.join("|", required) + " in row keys " + keys));
    }

    private void validateSqlMentions(
            EvidencePack.RequestResult rr,
            List<Issue> issues,
            Map<String, Object> trace
    ) {
        String sql = rr.sql().toLowerCase(Locale.ROOT);
        String kind = rr.kind() == null ? "" : rr.kind().toLowerCase(Locale.ROOT);
        boolean mentionsAgg = sql.contains("select");
        trace.put("sql_has_select", mentionsAgg);
        if ("correlation".equals(kind) && !sql.contains("corr(")) {
            issues.add(new Issue(rr.requestId(), rr.kind(), "WARN", "SQL_KIND_MISMATCH",
                    "correlation request SQL does not contain CORR("));
        }
        if ("time_series".equals(kind) && !(sql.contains("timestamp_trunc") || sql.contains("date_trunc")
                || sql.contains("period"))) {
            issues.add(new Issue(rr.requestId(), rr.kind(), "WARN", "SQL_KIND_MISMATCH",
                    "time_series SQL missing expected time truncation"));
        }
    }
}
