package com.example.BACKEND.catalogue.decision.presentation.executive;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Caps executive answers: 1 headline, 4 metrics, 1 chart, 2 short paragraphs.
 */
@Component
public class AnswerCompressionPolicy {

    public static final int MAX_METRICS = 4;
    public static final int MAX_PARAGRAPH_CHARS = 280;
    public static final int MAX_PARAGRAPHS = 2;

    public List<ExecutiveSupportingMetric> compressMetrics(List<ExecutiveSupportingMetric> metrics) {
        if (metrics == null) return List.of();
        return metrics.stream().limit(MAX_METRICS).toList();
    }

    public String compressParagraph(String text) {
        if (text == null || text.isBlank()) return "";
        String trimmed = text.trim().replaceAll("\\s{2,}", " ");
        if (trimmed.length() <= MAX_PARAGRAPH_CHARS) return trimmed;
        int cut = trimmed.lastIndexOf(' ', MAX_PARAGRAPH_CHARS);
        if (cut < 80) cut = MAX_PARAGRAPH_CHARS;
        return trimmed.substring(0, cut).trim() + "…";
    }

    public String joinParagraphs(String first, String second) {
        String a = compressParagraph(first);
        String b = compressParagraph(second);
        if (a.isBlank()) return b;
        if (b.isBlank() || a.equals(b)) return a;
        return a + " " + b;
    }

    public ChartSpec singleChart(ChartSpec chart) {
        return chart;
    }
}
