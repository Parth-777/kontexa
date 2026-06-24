package com.example.BACKEND.catalogue.agent.scale;

import java.time.LocalDate;

/**
 * Bounded date window for warehouse scans on medium/large tables.
 * Start/end are anchored to dates present in the data (e.g. last 90 distinct trip days),
 * not "today minus N days".
 */
public record AnalysisWindow(
        LocalDate start,
        LocalDate end,
        String dateColumn,
        boolean active
) {
    public static AnalysisWindow unrestricted() {
        return new AnalysisWindow(null, null, null, false);
    }

    /**
     * SQL fragment including leading space: " WHERE date >= ..." or "" if inactive.
     */
    public String whereClause(String dateColumnRef, String provider) {
        if (!active || start == null || dateColumnRef == null || dateColumnRef.isBlank()) {
            return "";
        }
        boolean isBQ = "bigquery".equalsIgnoreCase(provider);
        boolean isSF = "snowflake".equalsIgnoreCase(provider);
        String startLit = start.toString();
        String dateExpr = AgentSqlHelper.asDateExpr(dateColumnRef, provider);
        if (end != null) {
            if (isBQ) {
                return " WHERE " + dateExpr + " >= DATE '" + startLit + "'"
                        + " AND " + dateExpr + " <= DATE '" + end + "'";
            }
            if (isSF) {
                return " WHERE " + dateExpr + " >= '" + startLit + "'::DATE"
                        + " AND " + dateExpr + " <= '" + end + "'::DATE";
            }
            return " WHERE " + dateExpr + " >= '" + startLit + "'"
                    + " AND " + dateExpr + " <= '" + end + "'";
        }
        if (isBQ) return " WHERE " + dateExpr + " >= DATE '" + startLit + "'";
        if (isSF) return " WHERE " + dateExpr + " >= '" + startLit + "'::DATE";
        return " WHERE " + dateExpr + " >= '" + startLit + "'";
    }
}
