package com.example.BACKEND.experiment.phase1.comparison;

import com.example.BACKEND.experiment.phase1.Phase1FilterSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort WHERE-clause filter extraction from generated SQL (experiment only).
 */
final class SqlWhereFilterExtractor {

    private static final Pattern WHERE = Pattern.compile(
            "(?is)WHERE\\s+(.+?)(?:\\s+GROUP\\s+BY|\\s+ORDER\\s+BY|\\s+LIMIT\\s|$)");
    private static final Pattern PREDICATE = Pattern.compile(
            "([a-zA-Z_][a-zA-Z0-9_]*)\\s*(=|!=|<>|>=|<=|>|<|LIKE|IN)\\s*('([^']*)'|\"([^\"]*)\"|(\\S+))",
            Pattern.CASE_INSENSITIVE);

    private SqlWhereFilterExtractor() {}

    static List<Phase1FilterSpec> extract(String sql) {
        if (sql == null || sql.isBlank()) return List.of();
        Matcher wm = WHERE.matcher(sql);
        if (!wm.find()) return List.of();

        String clause = wm.group(1).trim();
        List<Phase1FilterSpec> out = new ArrayList<>();
        for (String part : clause.split("(?i)\\s+AND\\s+")) {
            Matcher pm = PREDICATE.matcher(part.trim());
            if (!pm.find()) continue;
            String col = pm.group(1);
            String op = pm.group(2).toUpperCase(Locale.ROOT);
            String val = pm.group(4) != null ? pm.group(4)
                    : pm.group(5) != null ? pm.group(5) : pm.group(6);
            if (val != null) out.add(new Phase1FilterSpec(col, op, val));
        }
        return List.copyOf(out);
    }
}
