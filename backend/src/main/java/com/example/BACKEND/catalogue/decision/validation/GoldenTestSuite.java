package com.example.BACKEND.catalogue.decision.validation;

import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.validation.AnalyticalTestCase.SqlAssertion;
import com.example.BACKEND.catalogue.decision.validation.AnalyticalTestCase.SqlAssertion.AssertionType;
import com.example.BACKEND.catalogue.decision.validation.AnalyticalTestCase.ValidationSchemaSpec;
import com.example.BACKEND.catalogue.decision.validation.AnalyticalTestCase.ValidationSchemaSpec.ColumnSpec;

import java.util.List;

/**
 * Canonical golden test suite for the {@link ExecutionValidationHarness}.
 *
 * 54 test cases across all 9 intent types, using three generic domain-agnostic schemas:
 *
 *   TRANSACTIONS — financial transaction events with timestamp, amount, category, channel, qty, region
 *   EVENTS       — activity events with timestamp, event_value, event_type, source, duration
 *   ORDERS       — order records with timestamp, total_value, region, status, item_count
 *
 * No domain-specific column names are used in assertion patterns.
 * All SQL assertions use generic aggregate/function keywords only.
 */
public final class GoldenTestSuite {

    private GoldenTestSuite() {}

    // ─── Reusable schemas ─────────────────────────────────────────────────

    static final ValidationSchemaSpec TRANSACTIONS_SCHEMA = new ValidationSchemaSpec(
            "transactions",
            List.of(
                    new ColumnSpec("transaction_id", "VARCHAR",  false, false),
                    new ColumnSpec("created_at",     "TIMESTAMP",false, true),
                    new ColumnSpec("amount",         "FLOAT",    true,  false),
                    new ColumnSpec("category",       "VARCHAR",  false, true),
                    new ColumnSpec("channel",        "VARCHAR",  false, true),
                    new ColumnSpec("quantity",       "INT",      true,  false),
                    new ColumnSpec("region",         "VARCHAR",  false, true)
            )
    );

    static final ValidationSchemaSpec EVENTS_SCHEMA = new ValidationSchemaSpec(
            "events",
            List.of(
                    new ColumnSpec("event_id",          "VARCHAR",  false, false),
                    new ColumnSpec("occurred_at",       "TIMESTAMP",false, true),
                    new ColumnSpec("event_value",       "FLOAT",    true,  false),
                    new ColumnSpec("event_type",        "VARCHAR",  false, true),
                    new ColumnSpec("source",            "VARCHAR",  false, true),
                    new ColumnSpec("duration_seconds",  "INT",      true,  false)
            )
    );

    static final ValidationSchemaSpec ORDERS_SCHEMA = new ValidationSchemaSpec(
            "orders",
            List.of(
                    new ColumnSpec("order_id",    "VARCHAR",  false, false),
                    new ColumnSpec("placed_at",   "TIMESTAMP",false, true),
                    new ColumnSpec("total_value", "FLOAT",    true,  false),
                    new ColumnSpec("region",      "VARCHAR",  false, true),
                    new ColumnSpec("status",      "VARCHAR",  false, true),
                    new ColumnSpec("item_count",  "INT",      true,  false)
            )
    );

    // ─── Assertion helpers ────────────────────────────────────────────────

    private static SqlAssertion must(String id, String desc, String regex) {
        return new SqlAssertion(id, desc, regex, AssertionType.MUST_CONTAIN);
    }
    private static SqlAssertion mustNot(String id, String desc, String regex) {
        return new SqlAssertion(id, desc, regex, AssertionType.MUST_NOT_CONTAIN);
    }

    // ─── Test suite factory ───────────────────────────────────────────────

    public static List<AnalyticalTestCase> all() {
        return List.of(

            // ═══════════════════════════════════════════════════════════════
            // RANKING (12 cases)
            // ═══════════════════════════════════════════════════════════════

            new AnalyticalTestCase("R01",
                "Which hours generate most revenue?",
                "RANKING", AnalyticalIntentType.RANKING,
                List.of("hour", "time", "revenue"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("R01-S1", "SQL must EXTRACT hour from timestamp", "(?i)EXTRACT\\s*\\(\\s*HOUR"),
                    must("R01-S2", "SQL must aggregate value metric with SUM", "(?i)SUM\\s*\\("),
                    must("R01-S3", "SQL must GROUP BY", "(?i)GROUP\\s+BY"),
                    must("R01-S4", "SQL must ORDER BY descending", "(?i)ORDER\\s+BY.+DESC"),
                    mustNot("R01-N1", "SQL must NOT be a generic total only", "^SELECT\\s+SUM\\([^)]+\\)\\s+FROM")
                ),
                List.of("EXTRACT(HOUR"),
                "Temporal ranking by hour — must derive hour_of_day and rank"
            ),

            new AnalyticalTestCase("R02",
                "Which days of the week have the highest transaction volume?",
                "RANKING", AnalyticalIntentType.RANKING,
                List.of("day", "week", "volume"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("R02-S1", "SQL must EXTRACT day-of-week", "(?i)EXTRACT\\s*\\(\\s*(DOW|DAYOFWEEK|WEEKDAY)"),
                    must("R02-S2", "SQL must GROUP BY", "(?i)GROUP\\s+BY"),
                    must("R02-S3", "SQL must aggregate", "(?i)(SUM|COUNT)\\s*\\(")
                ),
                List.of("EXTRACT(DOW", "EXTRACT(DAYOFWEEK"),
                "Temporal ranking by weekday — must extract day-of-week"
            ),

            new AnalyticalTestCase("R03",
                "What are the top 10 revenue-generating categories?",
                "RANKING", AnalyticalIntentType.RANKING,
                List.of("category", "revenue", "ranking"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("R03-S1", "SQL must GROUP BY categorical dimension", "(?i)GROUP\\s+BY\\s+\\w"),
                    must("R03-S2", "SQL must SUM the value metric", "(?i)SUM\\s*\\("),
                    must("R03-S3", "SQL must ORDER BY DESC", "(?i)ORDER\\s+BY.+DESC"),
                    must("R03-S4", "SQL must have LIMIT", "(?i)LIMIT\\s+\\d+")
                ),
                List.of("category"),
                "Entity-level ranking by category"
            ),

            new AnalyticalTestCase("R04",
                "Which channel performs best by total value?",
                "RANKING", AnalyticalIntentType.RANKING,
                List.of("channel", "performance", "value"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("R04-S1", "SQL must GROUP BY channel dimension", "(?i)GROUP\\s+BY"),
                    must("R04-S2", "SQL must aggregate value", "(?i)SUM\\s*\\("),
                    must("R04-S3", "SQL must ORDER BY DESC", "(?i)ORDER\\s+BY.+DESC")
                ),
                List.of("channel"),
                "Channel-level ranking by total value"
            ),

            new AnalyticalTestCase("R05",
                "What are the top regions by order value?",
                "RANKING", AnalyticalIntentType.RANKING,
                List.of("region", "order", "value"),
                ORDERS_SCHEMA,
                List.of(
                    must("R05-S1", "SQL must GROUP BY region", "(?i)GROUP\\s+BY"),
                    must("R05-S2", "SQL must SUM total_value", "(?i)SUM\\s*\\(\\s*total_value"),
                    must("R05-S3", "SQL must ORDER BY DESC", "(?i)ORDER\\s+BY.+DESC"),
                    must("R05-S4", "SQL must LIMIT results", "(?i)LIMIT\\s+\\d+")
                ),
                List.of("region"),
                "Orders ranked by region using total_value"
            ),

            new AnalyticalTestCase("R06",
                "Which event types generate the most value?",
                "RANKING", AnalyticalIntentType.RANKING,
                List.of("event_type", "value", "ranking"),
                EVENTS_SCHEMA,
                List.of(
                    must("R06-S1", "SQL must GROUP BY event_type", "(?i)GROUP\\s+BY"),
                    must("R06-S2", "SQL must SUM event_value", "(?i)SUM\\s*\\(\\s*event_value"),
                    must("R06-S3", "SQL must ORDER BY DESC", "(?i)ORDER\\s+BY.+DESC")
                ),
                List.of("event_type"),
                "Event type ranking by total value"
            ),

            new AnalyticalTestCase("R07",
                "Rank sources by total event contribution",
                "RANKING", AnalyticalIntentType.RANKING,
                List.of("source", "contribution", "ranking"),
                EVENTS_SCHEMA,
                List.of(
                    must("R07-S1", "SQL must GROUP BY source", "(?i)GROUP\\s+BY"),
                    must("R07-S2", "SQL must aggregate value", "(?i)SUM\\s*\\("),
                    must("R07-S3", "SQL must ORDER BY DESC", "(?i)ORDER\\s+BY.+DESC")
                ),
                List.of("source"),
                "Source ranking by total event value"
            ),

            new AnalyticalTestCase("R08",
                "Which categories rank lowest in revenue?",
                "RANKING", AnalyticalIntentType.RANKING,
                List.of("category", "revenue", "lowest"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("R08-S1", "SQL must GROUP BY", "(?i)GROUP\\s+BY"),
                    must("R08-S2", "SQL must SUM amount", "(?i)SUM\\s*\\("),
                    must("R08-S3", "SQL must ORDER BY", "(?i)ORDER\\s+BY")
                ),
                List.of("category"),
                "Bottom ranking by revenue — must ORDER BY (ASC or DESC with LIMIT)"
            ),

            new AnalyticalTestCase("R09",
                "What hours see the least activity?",
                "RANKING", AnalyticalIntentType.RANKING,
                List.of("hour", "time", "least", "activity"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("R09-S1", "SQL must EXTRACT hour", "(?i)EXTRACT\\s*\\(\\s*HOUR"),
                    must("R09-S2", "SQL must GROUP BY", "(?i)GROUP\\s+BY"),
                    must("R09-S3", "SQL must aggregate", "(?i)(SUM|COUNT)\\s*\\(")
                ),
                List.of("EXTRACT(HOUR"),
                "Lowest-hour temporal ranking"
            ),

            new AnalyticalTestCase("R10",
                "Top 20 regions by order count",
                "RANKING", AnalyticalIntentType.RANKING,
                List.of("region", "count", "orders"),
                ORDERS_SCHEMA,
                List.of(
                    must("R10-S1", "SQL must GROUP BY region", "(?i)GROUP\\s+BY"),
                    must("R10-S2", "SQL must aggregate", "(?i)(SUM|COUNT)\\s*\\("),
                    must("R10-S3", "SQL must ORDER BY DESC", "(?i)ORDER\\s+BY.+DESC"),
                    must("R10-S4", "SQL must LIMIT", "(?i)LIMIT\\s+\\d+")
                ),
                List.of("region"),
                "Regional ranking by order count with limit"
            ),

            new AnalyticalTestCase("R11",
                "Which months generate the most revenue?",
                "RANKING", AnalyticalIntentType.RANKING,
                List.of("month", "revenue", "ranking"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("R11-S1", "SQL must bucket by month", "(?i)(DATE_TRUNC|EXTRACT\\s*\\(\\s*MONTH)"),
                    must("R11-S2", "SQL must SUM value", "(?i)SUM\\s*\\("),
                    must("R11-S3", "SQL must GROUP BY", "(?i)GROUP\\s+BY"),
                    must("R11-S4", "SQL must ORDER BY", "(?i)ORDER\\s+BY")
                ),
                List.of("DATE_TRUNC", "EXTRACT(MONTH"),
                "Monthly revenue ranking — must derive month bucket"
            ),

            new AnalyticalTestCase("R12",
                "Which status types have the most orders?",
                "RANKING", AnalyticalIntentType.RANKING,
                List.of("status", "orders", "count"),
                ORDERS_SCHEMA,
                List.of(
                    must("R12-S1", "SQL must GROUP BY status", "(?i)GROUP\\s+BY"),
                    must("R12-S2", "SQL must aggregate", "(?i)(SUM|COUNT)\\s*\\("),
                    must("R12-S3", "SQL must ORDER BY", "(?i)ORDER\\s+BY")
                ),
                List.of("status"),
                "Status-level order count ranking"
            ),

            // ═══════════════════════════════════════════════════════════════
            // CONTRIBUTION (10 cases)
            // ═══════════════════════════════════════════════════════════════

            new AnalyticalTestCase("C01",
                "What percentage of revenue comes from each category?",
                "CONTRIBUTION", AnalyticalIntentType.CONTRIBUTION,
                List.of("category", "percentage", "revenue"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("C01-S1", "SQL must GROUP BY category", "(?i)GROUP\\s+BY"),
                    must("C01-S2", "SQL must SUM amount", "(?i)SUM\\s*\\("),
                    must("C01-S3", "SQL must compute share percentage", "(?i)(share_pct|100\\.0\\s*\\*|\\*\\s*100)")
                ),
                List.of("category"),
                "Contribution share per category — must produce share_pct"
            ),

            new AnalyticalTestCase("C02",
                "How much does each channel contribute to total sales?",
                "CONTRIBUTION", AnalyticalIntentType.CONTRIBUTION,
                List.of("channel", "contribution", "sales"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("C02-S1", "SQL must GROUP BY", "(?i)GROUP\\s+BY"),
                    must("C02-S2", "SQL must aggregate value", "(?i)SUM\\s*\\("),
                    must("C02-S3", "SQL must include share calculation", "(?i)(share_pct|OVER\\s*\\(\\s*\\)|100)")
                ),
                List.of("channel"),
                "Channel contribution to total sales"
            ),

            new AnalyticalTestCase("C03",
                "What share of total orders does each region represent?",
                "CONTRIBUTION", AnalyticalIntentType.CONTRIBUTION,
                List.of("region", "share", "orders"),
                ORDERS_SCHEMA,
                List.of(
                    must("C03-S1", "SQL must GROUP BY region", "(?i)GROUP\\s+BY"),
                    must("C03-S2", "SQL must aggregate", "(?i)(SUM|COUNT)\\s*\\("),
                    must("C03-S3", "SQL must compute share", "(?i)(share_pct|OVER|100)")
                ),
                List.of("region"),
                "Regional share of total orders"
            ),

            new AnalyticalTestCase("C04",
                "What percentage of events come from each source?",
                "CONTRIBUTION", AnalyticalIntentType.CONTRIBUTION,
                List.of("source", "percentage", "events"),
                EVENTS_SCHEMA,
                List.of(
                    must("C04-S1", "SQL must GROUP BY source", "(?i)GROUP\\s+BY"),
                    must("C04-S2", "SQL must aggregate", "(?i)(SUM|COUNT)\\s*\\("),
                    must("C04-S3", "SQL must compute share", "(?i)(share_pct|OVER|100)")
                ),
                List.of("source"),
                "Source contribution as percentage of total events"
            ),

            new AnalyticalTestCase("C05",
                "How does each event type contribute to total value?",
                "CONTRIBUTION", AnalyticalIntentType.CONTRIBUTION,
                List.of("event_type", "contribution", "value"),
                EVENTS_SCHEMA,
                List.of(
                    must("C05-S1", "SQL must GROUP BY event_type", "(?i)GROUP\\s+BY"),
                    must("C05-S2", "SQL must SUM event_value", "(?i)SUM\\s*\\(\\s*event_value"),
                    must("C05-S3", "SQL must compute share", "(?i)(share_pct|OVER|100)")
                ),
                List.of("event_type"),
                "Event type contribution to total value with share_pct"
            ),

            new AnalyticalTestCase("C06",
                "What fraction of transactions happen at each hour?",
                "CONTRIBUTION", AnalyticalIntentType.CONTRIBUTION,
                List.of("hour", "fraction", "time"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("C06-S1", "SQL must EXTRACT hour", "(?i)EXTRACT\\s*\\(\\s*HOUR"),
                    must("C06-S2", "SQL must GROUP BY", "(?i)GROUP\\s+BY"),
                    must("C06-S3", "SQL must aggregate", "(?i)(SUM|COUNT)\\s*\\(")
                ),
                List.of("EXTRACT(HOUR"),
                "Hourly contribution share — temporal + contribution"
            ),

            new AnalyticalTestCase("C07",
                "Which category has the most concentrated revenue?",
                "CONTRIBUTION", AnalyticalIntentType.CONTRIBUTION,
                List.of("category", "concentration", "revenue"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("C07-S1", "SQL must GROUP BY", "(?i)GROUP\\s+BY"),
                    must("C07-S2", "SQL must SUM amount", "(?i)SUM\\s*\\("),
                    must("C07-S3", "SQL must ORDER BY DESC", "(?i)ORDER\\s+BY.+DESC")
                ),
                List.of("category"),
                "Revenue concentration — must rank categories by contribution"
            ),

            new AnalyticalTestCase("C08",
                "Show revenue contribution breakdown by month",
                "CONTRIBUTION", AnalyticalIntentType.CONTRIBUTION,
                List.of("month", "revenue", "breakdown"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("C08-S1", "SQL must bucket by month", "(?i)(DATE_TRUNC|EXTRACT\\s*\\(\\s*MONTH)"),
                    must("C08-S2", "SQL must SUM value", "(?i)SUM\\s*\\("),
                    must("C08-S3", "SQL must GROUP BY", "(?i)GROUP\\s+BY")
                ),
                List.of("DATE_TRUNC", "EXTRACT(MONTH"),
                "Monthly contribution breakdown — temporal + contribution"
            ),

            new AnalyticalTestCase("C09",
                "Which regions drive the majority of order value?",
                "CONTRIBUTION", AnalyticalIntentType.CONTRIBUTION,
                List.of("region", "majority", "value"),
                ORDERS_SCHEMA,
                List.of(
                    must("C09-S1", "SQL must GROUP BY region", "(?i)GROUP\\s+BY"),
                    must("C09-S2", "SQL must SUM total_value", "(?i)SUM\\s*\\("),
                    must("C09-S3", "SQL must ORDER BY DESC", "(?i)ORDER\\s+BY.+DESC")
                ),
                List.of("region"),
                "Pareto analysis — which regions drive most value"
            ),

            new AnalyticalTestCase("C10",
                "What proportion of transactions happen in each region?",
                "CONTRIBUTION", AnalyticalIntentType.CONTRIBUTION,
                List.of("region", "proportion", "transactions"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("C10-S1", "SQL must GROUP BY region", "(?i)GROUP\\s+BY"),
                    must("C10-S2", "SQL must aggregate", "(?i)(SUM|COUNT)\\s*\\("),
                    must("C10-S3", "SQL must compute share", "(?i)(share_pct|OVER|100)")
                ),
                List.of("region"),
                "Regional share of transaction volume"
            ),

            // ═══════════════════════════════════════════════════════════════
            // EFFICIENCY (8 cases)
            // ═══════════════════════════════════════════════════════════════

            new AnalyticalTestCase("E01",
                "Which categories have the highest revenue per unit?",
                "EFFICIENCY", AnalyticalIntentType.RANKING,
                List.of("category", "revenue", "per", "unit", "efficiency"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("E01-S1", "SQL must GROUP BY category", "(?i)GROUP\\s+BY"),
                    must("E01-S2", "SQL must SUM both value and volume metrics", "(?i)SUM\\s*\\("),
                    must("E01-S3", "SQL must compute efficiency ratio", "(?i)(efficiency_ratio|CAST.+FLOAT|\\bNULLIF\\b)")
                ),
                List.of("category"),
                "Revenue-per-unit efficiency — must derive ratio SUM(amount)/SUM(quantity)"
            ),

            new AnalyticalTestCase("E02",
                "What event types give the most value per second?",
                "EFFICIENCY", AnalyticalIntentType.RANKING,
                List.of("event_type", "value", "per", "second", "efficiency"),
                EVENTS_SCHEMA,
                List.of(
                    must("E02-S1", "SQL must GROUP BY event_type", "(?i)GROUP\\s+BY"),
                    must("E02-S2", "SQL must SUM event_value", "(?i)SUM\\s*\\(\\s*event_value"),
                    must("E02-S3", "SQL must compute ratio", "(?i)(efficiency_ratio|NULLIF)")
                ),
                List.of("event_type"),
                "Value-per-second efficiency for event types"
            ),

            new AnalyticalTestCase("E03",
                "Which channel generates the most value per transaction?",
                "EFFICIENCY", AnalyticalIntentType.RANKING,
                List.of("channel", "value", "per", "efficiency"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("E03-S1", "SQL must GROUP BY", "(?i)GROUP\\s+BY"),
                    must("E03-S2", "SQL must aggregate", "(?i)SUM\\s*\\("),
                    must("E03-S3", "SQL must ORDER BY efficiency DESC", "(?i)ORDER\\s+BY.+DESC")
                ),
                List.of("channel"),
                "Channel efficiency ranking — value per occurrence"
            ),

            new AnalyticalTestCase("E04",
                "What region has the best value per item ordered?",
                "EFFICIENCY", AnalyticalIntentType.RANKING,
                List.of("region", "value", "per", "item", "efficiency"),
                ORDERS_SCHEMA,
                List.of(
                    must("E04-S1", "SQL must GROUP BY region", "(?i)GROUP\\s+BY"),
                    must("E04-S2", "SQL must SUM total_value and item_count", "(?i)SUM\\s*\\("),
                    must("E04-S3", "SQL must compute efficiency ratio", "(?i)(efficiency_ratio|NULLIF)")
                ),
                List.of("region"),
                "Order value-per-item efficiency by region"
            ),

            new AnalyticalTestCase("E05",
                "Which source has highest event value per occurrence?",
                "EFFICIENCY", AnalyticalIntentType.RANKING,
                List.of("source", "value", "per", "occurrence", "efficiency"),
                EVENTS_SCHEMA,
                List.of(
                    must("E05-S1", "SQL must GROUP BY source", "(?i)GROUP\\s+BY"),
                    must("E05-S2", "SQL must aggregate", "(?i)SUM\\s*\\("),
                    must("E05-S3", "SQL must ORDER BY DESC", "(?i)ORDER\\s+BY.+DESC")
                ),
                List.of("source"),
                "Source efficiency — value per occurrence"
            ),

            new AnalyticalTestCase("E06",
                "Which day of week is most revenue-efficient?",
                "EFFICIENCY", AnalyticalIntentType.RANKING,
                List.of("day", "week", "efficiency", "revenue"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("E06-S1", "SQL must EXTRACT day-of-week", "(?i)EXTRACT\\s*\\(\\s*(DOW|DAYOFWEEK)"),
                    must("E06-S2", "SQL must GROUP BY", "(?i)GROUP\\s+BY"),
                    must("E06-S3", "SQL must aggregate", "(?i)SUM\\s*\\(")
                ),
                List.of("EXTRACT(DOW"),
                "Weekday efficiency — temporal + efficiency combined"
            ),

            new AnalyticalTestCase("E07",
                "Which categories are the most and least efficient?",
                "EFFICIENCY", AnalyticalIntentType.RANKING,
                List.of("category", "efficiency", "comparison"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("E07-S1", "SQL must GROUP BY", "(?i)GROUP\\s+BY"),
                    must("E07-S2", "SQL must SUM", "(?i)SUM\\s*\\("),
                    must("E07-S3", "SQL must ORDER BY", "(?i)ORDER\\s+BY")
                ),
                List.of("category"),
                "Efficiency spread — top and bottom categories"
            ),

            new AnalyticalTestCase("E08",
                "Which regions have the best order value per item?",
                "EFFICIENCY", AnalyticalIntentType.RANKING,
                List.of("region", "value", "per", "item"),
                ORDERS_SCHEMA,
                List.of(
                    must("E08-S1", "SQL must GROUP BY region", "(?i)GROUP\\s+BY"),
                    must("E08-S2", "SQL must SUM", "(?i)SUM\\s*\\("),
                    must("E08-S3", "SQL must compute ratio", "(?i)(efficiency_ratio|NULLIF|CAST)")
                ),
                List.of("region"),
                "Region efficiency — value per item"
            ),

            // ═══════════════════════════════════════════════════════════════
            // TREND (8 cases)
            // ═══════════════════════════════════════════════════════════════

            new AnalyticalTestCase("T01",
                "How has revenue trended over time?",
                "TREND", AnalyticalIntentType.TREND_ANALYSIS,
                List.of("revenue", "trend", "time"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("T01-S1", "SQL must bucket time", "(?i)(DATE_TRUNC|EXTRACT\\s*\\(\\s*MONTH)"),
                    must("T01-S2", "SQL must SUM amount", "(?i)SUM\\s*\\("),
                    must("T01-S3", "SQL must GROUP BY time bucket", "(?i)GROUP\\s+BY"),
                    must("T01-S4", "SQL must ORDER BY time ASC", "(?i)ORDER\\s+BY.+ASC")
                ),
                List.of("DATE_TRUNC", "EXTRACT(MONTH"),
                "Revenue trend — chronological ordering required"
            ),

            new AnalyticalTestCase("T02",
                "Show me the monthly transaction volume trend",
                "TREND", AnalyticalIntentType.TREND_ANALYSIS,
                List.of("monthly", "trend", "volume"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("T02-S1", "SQL must bucket by month", "(?i)(DATE_TRUNC|EXTRACT\\s*\\(\\s*MONTH)"),
                    must("T02-S2", "SQL must aggregate volume", "(?i)(SUM|COUNT)\\s*\\("),
                    must("T02-S3", "SQL must GROUP BY", "(?i)GROUP\\s+BY"),
                    must("T02-S4", "SQL must ORDER chronologically", "(?i)ORDER\\s+BY.+ASC")
                ),
                List.of("DATE_TRUNC", "EXTRACT(MONTH"),
                "Monthly volume trend — must be ordered chronologically"
            ),

            new AnalyticalTestCase("T03",
                "How are order values changing over time?",
                "TREND", AnalyticalIntentType.TREND_ANALYSIS,
                List.of("order", "values", "trend"),
                ORDERS_SCHEMA,
                List.of(
                    must("T03-S1", "SQL must bucket time", "(?i)(DATE_TRUNC|EXTRACT\\s*\\(\\s*MONTH)"),
                    must("T03-S2", "SQL must SUM total_value", "(?i)SUM\\s*\\(\\s*total_value"),
                    must("T03-S3", "SQL must ORDER chronologically", "(?i)ORDER\\s+BY.+ASC")
                ),
                List.of("DATE_TRUNC"),
                "Order value trend over time"
            ),

            new AnalyticalTestCase("T04",
                "What is the growth pattern of events by month?",
                "TREND", AnalyticalIntentType.TREND_ANALYSIS,
                List.of("month", "growth", "events"),
                EVENTS_SCHEMA,
                List.of(
                    must("T04-S1", "SQL must bucket by month", "(?i)(DATE_TRUNC|EXTRACT\\s*\\(\\s*MONTH)"),
                    must("T04-S2", "SQL must aggregate", "(?i)(SUM|COUNT)\\s*\\("),
                    must("T04-S3", "SQL must GROUP BY", "(?i)GROUP\\s+BY"),
                    must("T04-S4", "SQL must ORDER ASC", "(?i)ORDER\\s+BY.+ASC")
                ),
                List.of("DATE_TRUNC"),
                "Monthly event growth trend"
            ),

            new AnalyticalTestCase("T05",
                "Show quarterly revenue performance",
                "TREND", AnalyticalIntentType.TREND_ANALYSIS,
                List.of("quarterly", "revenue", "performance"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("T05-S1", "SQL must bucket by quarter", "(?i)(DATE_TRUNC\\s*\\(\\s*'quarter'|EXTRACT\\s*\\(\\s*QUARTER)"),
                    must("T05-S2", "SQL must SUM amount", "(?i)SUM\\s*\\("),
                    must("T05-S3", "SQL must GROUP BY", "(?i)GROUP\\s+BY")
                ),
                List.of("quarter"),
                "Quarterly revenue trend"
            ),

            new AnalyticalTestCase("T06",
                "How has order volume changed by quarter?",
                "TREND", AnalyticalIntentType.TREND_ANALYSIS,
                List.of("quarterly", "orders", "volume"),
                ORDERS_SCHEMA,
                List.of(
                    must("T06-S1", "SQL must bucket time", "(?i)(DATE_TRUNC|EXTRACT\\s*\\(\\s*(QUARTER|MONTH))"),
                    must("T06-S2", "SQL must aggregate", "(?i)(SUM|COUNT)\\s*\\("),
                    must("T06-S3", "SQL must ORDER ASC", "(?i)ORDER\\s+BY.+ASC")
                ),
                List.of("DATE_TRUNC"),
                "Quarterly order volume trend"
            ),

            new AnalyticalTestCase("T07",
                "Which months show the strongest event volume?",
                "TREND", AnalyticalIntentType.TREND_ANALYSIS,
                List.of("month", "event_volume", "strongest"),
                EVENTS_SCHEMA,
                List.of(
                    must("T07-S1", "SQL must bucket by month", "(?i)(DATE_TRUNC|EXTRACT\\s*\\(\\s*MONTH)"),
                    must("T07-S2", "SQL must aggregate", "(?i)(SUM|COUNT)\\s*\\("),
                    must("T07-S3", "SQL must GROUP BY", "(?i)GROUP\\s+BY")
                ),
                List.of("DATE_TRUNC", "EXTRACT(MONTH"),
                "Monthly event volume identification"
            ),

            new AnalyticalTestCase("T08",
                "Are there seasonal patterns in revenue?",
                "TREND", AnalyticalIntentType.TREND_ANALYSIS,
                List.of("seasonal", "revenue", "pattern"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("T08-S1", "SQL must bucket time", "(?i)(DATE_TRUNC|EXTRACT\\s*\\(\\s*MONTH)"),
                    must("T08-S2", "SQL must SUM amount", "(?i)SUM\\s*\\("),
                    must("T08-S3", "SQL must ORDER chronologically", "(?i)ORDER\\s+BY.+ASC")
                ),
                List.of("DATE_TRUNC"),
                "Seasonal pattern detection — must produce chronological time series"
            ),

            // ═══════════════════════════════════════════════════════════════
            // SEGMENTATION (6 cases)
            // ═══════════════════════════════════════════════════════════════

            new AnalyticalTestCase("SEG01",
                "How is revenue distributed across categories?",
                "SEGMENTATION", AnalyticalIntentType.SEGMENTATION,
                List.of("category", "distribution", "revenue"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("SEG01-S1", "SQL must GROUP BY category", "(?i)GROUP\\s+BY"),
                    must("SEG01-S2", "SQL must SUM amount", "(?i)SUM\\s*\\("),
                    must("SEG01-S3", "SQL must ORDER BY", "(?i)ORDER\\s+BY")
                ),
                List.of("category"),
                "Revenue distribution by category"
            ),

            new AnalyticalTestCase("SEG02",
                "How are events split by type?",
                "SEGMENTATION", AnalyticalIntentType.SEGMENTATION,
                List.of("event_type", "split", "distribution"),
                EVENTS_SCHEMA,
                List.of(
                    must("SEG02-S1", "SQL must GROUP BY event_type", "(?i)GROUP\\s+BY"),
                    must("SEG02-S2", "SQL must aggregate", "(?i)(SUM|COUNT)\\s*\\(")
                ),
                List.of("event_type"),
                "Event type distribution segmentation"
            ),

            new AnalyticalTestCase("SEG03",
                "Break down orders by region and status",
                "SEGMENTATION", AnalyticalIntentType.SEGMENTATION,
                List.of("region", "status", "breakdown"),
                ORDERS_SCHEMA,
                List.of(
                    must("SEG03-S1", "SQL must GROUP BY", "(?i)GROUP\\s+BY"),
                    must("SEG03-S2", "SQL must aggregate", "(?i)(SUM|COUNT)\\s*\\(")
                ),
                List.of("region", "status"),
                "Composite segmentation — two dimensions"
            ),

            new AnalyticalTestCase("SEG04",
                "How is transaction volume distributed by channel?",
                "SEGMENTATION", AnalyticalIntentType.SEGMENTATION,
                List.of("channel", "volume", "distribution"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("SEG04-S1", "SQL must GROUP BY channel", "(?i)GROUP\\s+BY"),
                    must("SEG04-S2", "SQL must aggregate", "(?i)(SUM|COUNT)\\s*\\(")
                ),
                List.of("channel"),
                "Channel-level volume segmentation"
            ),

            new AnalyticalTestCase("SEG05",
                "Show the distribution of order values by region",
                "SEGMENTATION", AnalyticalIntentType.SEGMENTATION,
                List.of("region", "order_values", "distribution"),
                ORDERS_SCHEMA,
                List.of(
                    must("SEG05-S1", "SQL must GROUP BY region", "(?i)GROUP\\s+BY"),
                    must("SEG05-S2", "SQL must SUM total_value", "(?i)SUM\\s*\\(\\s*total_value")
                ),
                List.of("region"),
                "Order value distribution by region"
            ),

            new AnalyticalTestCase("SEG06",
                "How are events segmented by source?",
                "SEGMENTATION", AnalyticalIntentType.SEGMENTATION,
                List.of("source", "segmentation", "events"),
                EVENTS_SCHEMA,
                List.of(
                    must("SEG06-S1", "SQL must GROUP BY source", "(?i)GROUP\\s+BY"),
                    must("SEG06-S2", "SQL must aggregate event_value", "(?i)SUM\\s*\\(\\s*event_value")
                ),
                List.of("source"),
                "Event segmentation by source"
            ),

            // ═══════════════════════════════════════════════════════════════
            // ANOMALY DETECTION (4 cases)
            // ═══════════════════════════════════════════════════════════════

            new AnalyticalTestCase("A01",
                "Which categories show abnormal revenue patterns?",
                "ANOMALY", AnalyticalIntentType.ANOMALY_DETECTION,
                List.of("category", "abnormal", "revenue"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("A01-S1", "SQL must GROUP BY category", "(?i)GROUP\\s+BY"),
                    must("A01-S2", "SQL must aggregate", "(?i)SUM\\s*\\("),
                    must("A01-S3", "SQL must ORDER BY", "(?i)ORDER\\s+BY")
                ),
                List.of("category"),
                "Anomaly detection by category — must rank to surface outliers"
            ),

            new AnalyticalTestCase("A02",
                "What hours show unusual transaction volume?",
                "ANOMALY", AnalyticalIntentType.ANOMALY_DETECTION,
                List.of("hour", "unusual", "volume"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("A02-S1", "SQL must EXTRACT hour", "(?i)EXTRACT\\s*\\(\\s*HOUR"),
                    must("A02-S2", "SQL must GROUP BY", "(?i)GROUP\\s+BY"),
                    must("A02-S3", "SQL must aggregate", "(?i)(SUM|COUNT)\\s*\\(")
                ),
                List.of("EXTRACT(HOUR"),
                "Hourly volume anomaly — must extract hour and rank"
            ),

            new AnalyticalTestCase("A03",
                "Which event sources have anomalous value patterns?",
                "ANOMALY", AnalyticalIntentType.ANOMALY_DETECTION,
                List.of("source", "anomalous", "value"),
                EVENTS_SCHEMA,
                List.of(
                    must("A03-S1", "SQL must GROUP BY source", "(?i)GROUP\\s+BY"),
                    must("A03-S2", "SQL must aggregate", "(?i)SUM\\s*\\("),
                    must("A03-S3", "SQL must ORDER BY", "(?i)ORDER\\s+BY")
                ),
                List.of("source"),
                "Source value anomaly detection"
            ),

            new AnalyticalTestCase("A04",
                "Are there outlier regions in order values?",
                "ANOMALY", AnalyticalIntentType.ANOMALY_DETECTION,
                List.of("region", "outlier", "order_values"),
                ORDERS_SCHEMA,
                List.of(
                    must("A04-S1", "SQL must GROUP BY region", "(?i)GROUP\\s+BY"),
                    must("A04-S2", "SQL must aggregate", "(?i)SUM\\s*\\("),
                    must("A04-S3", "SQL must ORDER BY", "(?i)ORDER\\s+BY")
                ),
                List.of("region"),
                "Regional outlier detection in order values"
            ),

            // ═══════════════════════════════════════════════════════════════
            // COMPARISON (6 cases)
            // ═══════════════════════════════════════════════════════════════

            new AnalyticalTestCase("CMP01",
                "Compare revenue between channels",
                "COMPARISON", AnalyticalIntentType.COMPARISON,
                List.of("channel", "compare", "revenue"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("CMP01-S1", "SQL must GROUP BY channel", "(?i)GROUP\\s+BY"),
                    must("CMP01-S2", "SQL must SUM amount", "(?i)SUM\\s*\\("),
                    must("CMP01-S3", "SQL must ORDER BY", "(?i)ORDER\\s+BY")
                ),
                List.of("channel"),
                "Cross-channel revenue comparison"
            ),

            new AnalyticalTestCase("CMP02",
                "How do regions compare on order value?",
                "COMPARISON", AnalyticalIntentType.COMPARISON,
                List.of("region", "compare", "order_value"),
                ORDERS_SCHEMA,
                List.of(
                    must("CMP02-S1", "SQL must GROUP BY region", "(?i)GROUP\\s+BY"),
                    must("CMP02-S2", "SQL must SUM total_value", "(?i)SUM\\s*\\("),
                    must("CMP02-S3", "SQL must ORDER BY", "(?i)ORDER\\s+BY")
                ),
                List.of("region"),
                "Regional order value comparison"
            ),

            new AnalyticalTestCase("CMP03",
                "Which event types underperform vs the average?",
                "COMPARISON", AnalyticalIntentType.COMPARISON,
                List.of("event_type", "underperform", "average"),
                EVENTS_SCHEMA,
                List.of(
                    must("CMP03-S1", "SQL must GROUP BY event_type", "(?i)GROUP\\s+BY"),
                    must("CMP03-S2", "SQL must aggregate event_value", "(?i)SUM\\s*\\(\\s*event_value"),
                    must("CMP03-S3", "SQL must ORDER BY", "(?i)ORDER\\s+BY")
                ),
                List.of("event_type"),
                "Below-average comparison for event types"
            ),

            new AnalyticalTestCase("CMP04",
                "Compare weekend vs weekday transaction volumes",
                "COMPARISON", AnalyticalIntentType.COMPARISON,
                List.of("weekend", "weekday", "compare", "time"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("CMP04-S1", "SQL must EXTRACT day-of-week", "(?i)EXTRACT\\s*\\(\\s*(DOW|DAYOFWEEK)"),
                    must("CMP04-S2", "SQL must GROUP BY", "(?i)GROUP\\s+BY"),
                    must("CMP04-S3", "SQL must aggregate", "(?i)(SUM|COUNT)\\s*\\(")
                ),
                List.of("EXTRACT(DOW"),
                "Weekend vs weekday comparison — must derive day-of-week"
            ),

            new AnalyticalTestCase("CMP05",
                "Which categories are below the revenue average?",
                "COMPARISON", AnalyticalIntentType.COMPARISON,
                List.of("category", "below", "average", "revenue"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("CMP05-S1", "SQL must GROUP BY category", "(?i)GROUP\\s+BY"),
                    must("CMP05-S2", "SQL must SUM amount", "(?i)SUM\\s*\\("),
                    must("CMP05-S3", "SQL must ORDER BY", "(?i)ORDER\\s+BY")
                ),
                List.of("category"),
                "Below-average revenue categories"
            ),

            new AnalyticalTestCase("CMP06",
                "Why does one category outperform others?",
                "ROOT_CAUSE", AnalyticalIntentType.ROOT_CAUSE_INVESTIGATION,
                List.of("category", "outperform", "reason"),
                TRANSACTIONS_SCHEMA,
                List.of(
                    must("CMP06-S1", "SQL must GROUP BY category", "(?i)GROUP\\s+BY"),
                    must("CMP06-S2", "SQL must aggregate", "(?i)SUM\\s*\\("),
                    must("CMP06-S3", "SQL must ORDER BY DESC", "(?i)ORDER\\s+BY.+DESC")
                ),
                List.of("category"),
                "Root cause investigation — must produce ranked comparison"
            )
        );
    }
}
