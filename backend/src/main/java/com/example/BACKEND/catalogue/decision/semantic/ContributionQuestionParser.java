package com.example.BACKEND.catalogue.decision.semantic;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses contribution/composition ratio questions.
 */
@Component
public class ContributionQuestionParser {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)how does (.+?) contribute to (.+?)\\??$"),
            Pattern.compile("(?i)contribution of (.+?) to (.+?)\\??$"),
            Pattern.compile("(?i)share of (.+?) in (.+?)\\??$"),
            Pattern.compile("(?i)what share of (.+?) comes from (.+?)\\??$")
    );

    private final QueryEntityResolver entityResolver;

    public ContributionQuestionParser(QueryEntityResolver entityResolver) {
        this.entityResolver = entityResolver;
    }

    public ContributionAnalysisPlan parse(String question) {
        if (question == null) return null;

        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(question.trim());
            if (m.find()) {
                String left = m.group(1).trim();
                String right = m.group(2).trim();
                boolean inverted = p.pattern().contains("comes from");
                ResolvedEntity first = entityResolver.matchFragment(left);
                ResolvedEntity second = entityResolver.matchFragment(right);
                ResolvedEntity numerator = inverted ? second : first;
                ResolvedEntity denominator = inverted ? first : second;

                if (numerator != null && denominator != null
                        && isMetric(numerator) && isMetric(denominator)
                        && !numerator.columnKey().equals(denominator.columnKey())) {
                    return ContributionAnalysisPlan.of(
                            numerator.columnKey(), numerator.label(),
                            denominator.columnKey(), denominator.label());
                }
            }
        }

        // "tips" + "revenue" fallback when pattern partial match
        List<ResolvedEntity> metrics = entityResolver.metrics(question);
        if (metrics.size() >= 2 && question.toLowerCase(Locale.ROOT).contains("contribute")) {
            ResolvedEntity num = metrics.stream()
                    .filter(m -> !"total_amount".equals(m.columnKey()))
                    .findFirst().orElse(metrics.getFirst());
            ResolvedEntity denom = metrics.stream()
                    .filter(m -> "total_amount".equals(m.columnKey()))
                    .findFirst()
                    .orElse(metrics.getLast());
            if (!num.columnKey().equals(denom.columnKey())) {
                return ContributionAnalysisPlan.of(
                        num.columnKey(), num.label(),
                        denom.columnKey(), denom.label());
            }
        }
        return null;
    }

    public boolean matches(String question) {
        if (question == null) return false;
        String q = question.toLowerCase(Locale.ROOT);
        return q.contains("contribute") || q.contains("share of") || q.contains("comes from");
    }

    private boolean isMetric(ResolvedEntity e) {
        return e.kind() == EntityKind.METRIC || e.kind() == EntityKind.DERIVED_METRIC;
    }
}
