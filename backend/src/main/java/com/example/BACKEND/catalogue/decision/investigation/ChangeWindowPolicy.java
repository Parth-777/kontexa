package com.example.BACKEND.catalogue.decision.investigation;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Derives baseline and observation windows generically from a time column, a grain, and
 * the latest data date. The observation window is the grain period containing the latest
 * date; the baseline window is the immediately preceding grain period.
 *
 * <p>The policy is dataset-agnostic: it performs pure calendar arithmetic and never inspects
 * question text or specific values.
 */
@Component
public class ChangeWindowPolicy {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    public Windows derive(String timeColumn, String grain, LocalDate latestDate) {
        String g = grain != null ? grain.toUpperCase(Locale.ROOT) : "MONTH";
        LocalDate obsStart;
        LocalDate obsEnd;
        switch (g) {
            case "DAY" -> {
                obsStart = latestDate;
                obsEnd = latestDate.plusDays(1);
            }
            case "WEEK" -> {
                obsStart = latestDate.minusDays((latestDate.getDayOfWeek().getValue() + 6) % 7);
                obsEnd = obsStart.plusWeeks(1);
            }
            case "QUARTER" -> {
                int startMonth = ((latestDate.getMonthValue() - 1) / 3) * 3 + 1;
                obsStart = LocalDate.of(latestDate.getYear(), startMonth, 1);
                obsEnd = obsStart.plusMonths(3);
            }
            case "YEAR" -> {
                obsStart = LocalDate.of(latestDate.getYear(), 1, 1);
                obsEnd = obsStart.plusYears(1);
            }
            default -> {
                obsStart = latestDate.withDayOfMonth(1);
                obsEnd = obsStart.plusMonths(1);
            }
        }
        LocalDate baseStart = baselineStart(obsStart, g);
        TimeWindow baseline = new TimeWindow(timeColumn, literal(baseStart), literal(obsStart));
        TimeWindow observation = new TimeWindow(timeColumn, literal(obsStart), literal(obsEnd));
        return new Windows(baseline, observation, g);
    }

    private static LocalDate baselineStart(LocalDate obsStart, String grain) {
        return switch (grain) {
            case "DAY" -> obsStart.minusDays(1);
            case "WEEK" -> obsStart.minusWeeks(1);
            case "QUARTER" -> obsStart.minusMonths(3);
            case "YEAR" -> obsStart.minusYears(1);
            default -> obsStart.minusMonths(1);
        };
    }

    private static String literal(LocalDate date) {
        return "'" + date.format(ISO) + "'";
    }

    public record Windows(TimeWindow baseline, TimeWindow observation, String grain) {}
}
