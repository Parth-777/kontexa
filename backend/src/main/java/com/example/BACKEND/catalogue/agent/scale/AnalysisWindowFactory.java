package com.example.BACKEND.catalogue.agent.scale;



import com.example.BACKEND.catalogue.agent.EnrichedColInfo;

import com.example.BACKEND.catalogue.agent.KpiDetectorService;

import com.example.BACKEND.catalogue.agent.TableContext;

import org.springframework.stereotype.Component;



import java.time.LocalDate;

import java.time.format.DateTimeParseException;

import java.util.List;

import java.util.Map;



/**

 * Builds analysis windows anchored to data in the warehouse — not the system clock.

 * For large tables, uses the N most recent distinct dates in the date column (e.g. last 90 trip days).

 */

@Component

public class AnalysisWindowFactory {



    private final ScaleProperties properties;

    private final ScaleAwareQueryExecutor queryExecutor;



    public AnalysisWindowFactory(ScaleProperties properties, ScaleAwareQueryExecutor queryExecutor) {

        this.properties = properties;

        this.queryExecutor = queryExecutor;

    }



    public AnalysisWindow forTable(

            KpiDetectorService.ColumnHints hints,

            Map<String, EnrichedColInfo> enriched,

            ScaleTier tier,

            TableContext ctxForMaxDate

    ) {

        if (!properties.isEnabled() || tier == ScaleTier.SMALL) {

            return AnalysisWindow.unrestricted();

        }



        String dateCol = hints.dateCol();

        if (dateCol == null || dateCol.isBlank()) {

            return AnalysisWindow.unrestricted();

        }



        int recentDateCount = properties.windowDays(tier);



        if (ctxForMaxDate != null) {

            AnalysisWindow fromWarehouse = resolveFromRecentDates(ctxForMaxDate, dateCol, recentDateCount);

            if (fromWarehouse != null) {

                return fromWarehouse;

            }

        }



        LocalDate end = resolveEndFromCatalogue(dateCol, enriched);

        if (end != null) {

            LocalDate start = end.minusDays(Math.max(1, recentDateCount) - 1L);

            if (start.isAfter(end)) start = end;

            logWindow(hints.tableName(), start, end, recentDateCount, "catalogue max");

            return new AnalysisWindow(start, end, dateCol, true);

        }



        System.out.printf(

                "[AnalysisWindow] %s — could not resolve data window; running without date filter%n",

                hints.tableName());

        return AnalysisWindow.unrestricted();

    }



    /**

     * Single warehouse query: MIN/MAX among the N most recent distinct dates in the table.

     */

    private AnalysisWindow resolveFromRecentDates(TableContext ctx, String dateCol, int recentDateCount) {

        try {

            String sql = AgentSqlHelper.recentDatesBoundsSql(ctx, dateCol, recentDateCount);



            TableContext probeCtx = new TableContext(

                    ctx.clientId(), ctx.hints(), ctx.enriched(), ctx.tableRef(), ctx.provider(),

                    ctx.useBQ(), ctx.useSF(), ctx.bqCfg(), ctx.sfCfg(), ctx.jdbcTemplate(),

                    ctx.tier(), ctx.rowCount(), AnalysisWindow.unrestricted(),

                    ctx.runContext(), ctx.tableRole());



            List<Map<String, Object>> rows = queryExecutor.execute(

                    sql, "Window: " + recentDateCount + " recent dates", probeCtx);

            if (rows.isEmpty()) return null;



            LocalDate start = parseDateCell(rows.get(0).get("window_start"));

            LocalDate end   = parseDateCell(rows.get(0).get("window_end"));

            if (start == null || end == null) return null;



            logWindow(ctx.hints().tableName(), start, end, recentDateCount, "warehouse recent dates");

            return new AnalysisWindow(start, end, dateCol, true);

        } catch (Exception e) {

            System.out.printf("[AnalysisWindow] recent-dates lookup failed: %s%n", e.getMessage());

            return null;

        }

    }



    private LocalDate resolveEndFromCatalogue(String dateCol, Map<String, EnrichedColInfo> enriched) {

        EnrichedColInfo info = enriched.get(dateCol.toLowerCase());

        if (info != null && info.maxValue() != null && info.maxValue().length() >= 10) {

            return parseDatePrefix(info.maxValue());

        }

        return null;

    }



    private LocalDate parseDateCell(Object value) {

        if (value == null) return null;

        String s = value.toString();

        if (s.length() >= 10) return parseDatePrefix(s.substring(0, 10));

        return parseDatePrefix(s);

    }



    private LocalDate parseDatePrefix(String value) {

        if (value == null || value.length() < 10) return null;

        try {

            return LocalDate.parse(value.substring(0, 10));

        } catch (DateTimeParseException e) {

            return null;

        }

    }



    private void logWindow(String table, LocalDate start, LocalDate end, int n, String source) {

        System.out.printf(

                "[AnalysisWindow] %s — %s to %s (%d most recent dates, %s)%n",

                table, start, end, n, source);

    }

}


