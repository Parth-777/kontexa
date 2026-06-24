package com.example.BACKEND.catalogue.decision.semantics.catalog;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts metric slot phrases from natural-language question patterns.
 */
@Component
public class QuestionSlotExtractor {

    private static final Pattern WHICH_HIGHEST = Pattern.compile(
            "(?i)which\\s+(.+?)\\s+(?:generates|generate|produces|produce|has|have|yields|yield)\\s+"
                    + "(?:the\\s+)?(?:highest|lowest|most|least|best|worst|maximum|minimum|max|min)\\s+(.+?)\\??$");

    private static final Pattern WHICH_IS_MOST = Pattern.compile(
            "(?i)which\\s+(.+?)\\s+is\\s+(?:the\\s+)?(?:most|least)\\s+(\\w+)\\??$");

    private static final Pattern WHICH_ARE = Pattern.compile(
            "(?i)which\\s+(.+?)\\s+are\\s+(?:the\\s+)?(?:most|least\\s+)?(\\w+)\\??$");

    private static final Pattern AFFECT = Pattern.compile(
            "(?i)how\\s+(?:does|do)\\s+(.+?)\\s+(?:affect|impact|influence|drive)\\s+(.+?)\\??$");

    private static final Pattern CORRELATE = Pattern.compile(
            "(?i)(?:does|do)\\s+(.+?)\\s+correlat(?:e|es|ion)?\\s+with\\s+(.+?)\\??$");

    private static final Pattern WHAT_DRIVES = Pattern.compile(
            "(?i)what\\s+drives?\\s+(.+?)\\??$");

    private static final Pattern RELATE = Pattern.compile(
            "(?i)how\\s+(?:does|do)\\s+(.+?)\\s+relat(?:e|es|ion)?\\s+(?:to|with)\\s+(.+?)\\??$");

    private static final Pattern ASSOCIATED = Pattern.compile(
            "(?i)(?:how\\s+(?:is|are)\\s+)?(.+?)\\s+associated\\s+with\\s+(.+?)\\??$");

    public QuestionMetricSlots extract(String question) {
        if (question == null || question.isBlank()) {
            return new QuestionMetricSlots(null, null, "", QuestionMetricSlots.SlotKind.GENERAL);
        }
        String q = question.trim();

        Matcher highest = WHICH_HIGHEST.matcher(q);
        if (highest.find()) {
            return new QuestionMetricSlots(
                    highest.group(2).trim(), null, q, QuestionMetricSlots.SlotKind.RANKING_METRIC);
        }

        Matcher isMost = WHICH_IS_MOST.matcher(q);
        if (isMost.find()) {
            return new QuestionMetricSlots(
                    isMost.group(2).trim(), null, q, QuestionMetricSlots.SlotKind.RANKING_METRIC);
        }

        Matcher are = WHICH_ARE.matcher(q);
        if (are.find()) {
            return new QuestionMetricSlots(
                    are.group(2).trim(), null, q, QuestionMetricSlots.SlotKind.RANKING_METRIC);
        }

        Matcher affect = AFFECT.matcher(q);
        if (affect.find()) {
            return new QuestionMetricSlots(
                    affect.group(2).trim(), affect.group(1).trim(), q,
                    QuestionMetricSlots.SlotKind.AFFECT_OUTCOME);
        }

        Matcher relate = RELATE.matcher(q);
        if (relate.find()) {
            return new QuestionMetricSlots(
                    relate.group(2).trim(), relate.group(1).trim(), q,
                    QuestionMetricSlots.SlotKind.CORRELATION_B);
        }

        Matcher assoc = ASSOCIATED.matcher(q);
        if (assoc.find()) {
            return new QuestionMetricSlots(
                    assoc.group(2).trim(), assoc.group(1).trim(), q,
                    QuestionMetricSlots.SlotKind.CORRELATION_B);
        }

        Matcher corr = CORRELATE.matcher(q);
        if (corr.find()) {
            return new QuestionMetricSlots(
                    corr.group(2).trim(), corr.group(1).trim(), q,
                    QuestionMetricSlots.SlotKind.CORRELATION_B);
        }

        Matcher drives = WHAT_DRIVES.matcher(q);
        if (drives.find()) {
            return new QuestionMetricSlots(
                    drives.group(1).trim(), null, q, QuestionMetricSlots.SlotKind.DRIVE_OUTCOME);
        }

        String lower = q.toLowerCase(Locale.ROOT);
        if (lower.contains("profitability")) {
            return new QuestionMetricSlots("profitability", null, q, QuestionMetricSlots.SlotKind.GENERAL);
        }
        if (lower.contains("profitable")) {
            return new QuestionMetricSlots("profitable", null, q, QuestionMetricSlots.SlotKind.GENERAL);
        }
        return new QuestionMetricSlots(null, null, q, QuestionMetricSlots.SlotKind.GENERAL);
    }

    public List<String> extractedPhrases(QuestionMetricSlots slots) {
        return slots.allPhrases();
    }
}
