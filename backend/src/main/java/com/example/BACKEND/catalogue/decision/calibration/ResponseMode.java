package com.example.BACKEND.catalogue.decision.calibration;

/**
 * The four response modes that govern how much intelligence the system expresses.
 *
 * Mode selection is deterministic — based on query type, materiality, and evidence
 * complexity. It is NOT based on keywords alone; it combines all signals.
 *
 * ┌──────────────────────────┬────────────────────────────────────────────────────┐
 * │ Mode                     │ Used for                                           │
 * ├──────────────────────────┼────────────────────────────────────────────────────┤
 * │ FACTUAL                  │ Contribution %, counts, KPI lookups, direct        │
 * │                          │ metric questions. Direct answer first.             │
 * ├──────────────────────────┼────────────────────────────────────────────────────┤
 * │ ANALYTICAL               │ Rankings, comparisons, trend analysis,             │
 * │                          │ segmentation. Evidence-heavy, moderate synthesis.  │
 * ├──────────────────────────┼────────────────────────────────────────────────────┤
 * │ INVESTIGATIVE            │ Anomalies, root-cause exploration, performance     │
 * │                          │ breakdowns, "why" questions. Deep drilldown.       │
 * ├──────────────────────────┼────────────────────────────────────────────────────┤
 * │ EXECUTIVE_STRATEGIC      │ Strategic risk, major business impact, executive   │
 * │                          │ briefings. Full implications + action orientation. │
 * └──────────────────────────┴────────────────────────────────────────────────────┘
 */
public enum ResponseMode {
    FACTUAL,
    ANALYTICAL,
    INVESTIGATIVE,
    EXECUTIVE_STRATEGIC
}
