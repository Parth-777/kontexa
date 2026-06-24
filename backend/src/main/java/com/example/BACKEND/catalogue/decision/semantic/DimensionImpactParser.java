package com.example.BACKEND.catalogue.decision.semantic;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses dimension-impact questions: revenue by distance, which zones drive revenue, etc.
 */
@Component
public class DimensionImpactParser {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)how does (.+?) affect (.+?)\\??$"),
            Pattern.compile("(?i)how does (.+?) impact (.+?)\\??$"),
            Pattern.compile("(?i)how does (.+?) influence (.+?)\\??$"),
            Pattern.compile("(?i)(.+?) by (.+?)\\??$"),
            Pattern.compile("(?i)which (.+?) (drive|generate|produce) (?:the )?(?:most|highest) (.+?)\\??$"),
            Pattern.compile("(?i)revenue by (.+?)\\??$")
    );

    private final QueryEntityResolver entityResolver;

    public DimensionImpactParser(QueryEntityResolver entityResolver) {
        this.entityResolver = entityResolver;
    }

    public DimensionImpactPlan parse(String question) {
        if (question == null) return null;
        String trimmed = question.trim();

        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(trimmed);
            if (m.find()) {
                DimensionImpactPlan plan = resolveFromGroups(m, p);
                if (plan != null) return plan;
            }
        }

        ResolvedEntity dimension = entityResolver.firstDimension(question);
        ResolvedEntity metric = entityResolver.firstMetric(question);
        if (dimension != null && metric != null && isImpactQuestion(question)) {
            return DimensionImpactPlan.of(
                    dimension.columnKey(), dimension.label(),
                    metric.columnKey(), metric.label());
        }
        return null;
    }

    public boolean matches(String question) {
        if (question == null) return false;
        String q = question.toLowerCase(Locale.ROOT);
        if (q.contains("contribute") || q.contains("share of")) return false;
        return q.contains("affect") || q.contains("impact") || q.contains("influence")
                || q.contains(" by ") || q.contains("drive") || q.contains("generate most");
    }

    private DimensionImpactPlan resolveFromGroups(Matcher m, Pattern p) {
        String pattern = p.pattern();
        if (pattern.contains("which")) {
            String dimFragment = m.group(1);
            String metricFragment = m.group(3);
            ResolvedEntity dim = entityResolver.matchFragment(dimFragment);
            ResolvedEntity metric = entityResolver.matchFragment(metricFragment);
            if (dim != null && metric != null && isDimension(dim)) {
                return DimensionImpactPlan.of(dim.columnKey(), dim.label(),
                        metric.columnKey(), metric.label());
            }
        } else if (pattern.contains("revenue by")) {
            ResolvedEntity dim = entityResolver.matchFragment(m.group(1));
            if (dim != null && isDimension(dim)) {
                return DimensionImpactPlan.of(dim.columnKey(), dim.label(),
                        "total_amount", "Total Revenue");
            }
        } else if (pattern.contains(" by ")) {
            String left = m.group(1);
            String right = m.group(2);
            ResolvedEntity metric = entityResolver.matchFragment(left);
            ResolvedEntity dim = entityResolver.matchFragment(right);
            if (metric != null && dim != null && isMetric(metric) && isDimension(dim)) {
                return DimensionImpactPlan.of(dim.columnKey(), dim.label(),
                        metric.columnKey(), metric.label());
            }
            // "revenue by trip distance" — metric may be in left only
            if (dim != null && isDimension(dim)) {
                metric = entityResolver.matchFragment(left);
                String metricKey = metric != null ? metric.columnKey() : "total_amount";
                String metricLabel = metric != null ? metric.label() : "Total Revenue";
                return DimensionImpactPlan.of(dim.columnKey(), dim.label(), metricKey, metricLabel);
            }
        } else {
            ResolvedEntity dim = entityResolver.matchFragment(m.group(1));
            ResolvedEntity metric = entityResolver.matchFragment(m.group(2));
            if (dim != null && metric != null && isDimension(dim) && isMetric(metric)) {
                return DimensionImpactPlan.of(dim.columnKey(), dim.label(),
                        metric.columnKey(), metric.label());
            }
        }
        return null;
    }

    private boolean isImpactQuestion(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        return q.contains("affect") || q.contains("impact") || q.contains("by ")
                || q.contains("drive") || q.contains("generate");
    }

    private boolean isDimension(ResolvedEntity e) {
        return e.kind() == EntityKind.DIMENSION || e.kind() == EntityKind.TEMPORAL_DIMENSION;
    }

    private boolean isMetric(ResolvedEntity e) {
        return e.kind() == EntityKind.METRIC || e.kind() == EntityKind.DERIVED_METRIC;
    }
}
