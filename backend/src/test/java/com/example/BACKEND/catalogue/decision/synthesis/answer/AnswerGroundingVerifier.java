package com.example.BACKEND.catalogue.decision.synthesis.answer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies that numeric claims in synthesized answers appear in warehouse row values.
 */
public final class AnswerGroundingVerifier {

    private static final Pattern NUMBER_TOKEN = Pattern.compile(
            "(?<![A-Za-z_])"
                    + "(?<num>"
                    + "\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?"
                    + "|\\d+(?:\\.\\d+)?"
                    + ")"
                    + "(?![A-Za-z_])");

    private AnswerGroundingVerifier() {}

    public static List<String> ungroundedNumbers(
            String narrative,
            List<String> keyFindings,
            List<Map<String, Object>> warehouseRows
    ) {
        Set<BigDecimal> allowed = normalizedNumbersFromRows(warehouseRows);
        List<String> violations = new ArrayList<>();

        for (BigDecimal n : normalizedNumbersFromText(narrative)) {
            if (!allowed.contains(n)) {
                violations.add("narrative: " + n.toPlainString());
            }
        }
        if (keyFindings != null) {
            for (String finding : keyFindings) {
                for (BigDecimal n : normalizedNumbersFromText(finding)) {
                    if (!allowed.contains(n)) {
                        violations.add("finding: " + n.toPlainString());
                    }
                }
            }
        }
        return violations;
    }

    public static void assertFullyGrounded(
            AnswerSynthesisOutput output,
            List<Map<String, Object>> warehouseRows
    ) {
        List<String> violations = ungroundedNumbers(
                output.executiveSummary(),
                output.keyFindings(),
                warehouseRows);
        if (!violations.isEmpty()) {
            throw new AssertionError(
                    "Ungrounded numbers in answer (not present in warehouse rows): " + violations);
        }
    }

    static Set<BigDecimal> normalizedNumbersFromRows(List<Map<String, Object>> rows) {
        Set<BigDecimal> out = new LinkedHashSet<>();
        if (rows == null) {
            return out;
        }
        for (Map<String, Object> row : rows) {
            collectNumbers(row, out);
        }
        return out;
    }

    private static void collectNumbers(Object value, Set<BigDecimal> out) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                collectNumbers(v, out);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable && !(value instanceof String)) {
            for (Object item : iterable) {
                collectNumbers(item, out);
            }
            return;
        }
        BigDecimal n = toNormalizedNumber(value);
        if (n != null) {
            out.add(n);
        }
    }

    static List<BigDecimal> normalizedNumbersFromText(String text) {
        List<BigDecimal> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher matcher = NUMBER_TOKEN.matcher(text);
        while (matcher.find()) {
            BigDecimal n = toNormalizedNumber(matcher.group("num"));
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }

    private static BigDecimal toNormalizedNumber(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            String s = raw.toString().trim().replace(",", "");
            if (s.isEmpty()) {
                return null;
            }
            BigDecimal n = new BigDecimal(s);
            if (n.scale() > 4) {
                n = n.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
            }
            return n.stripTrailingZeros();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
