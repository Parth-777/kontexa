package com.example.BACKEND.catalogue.agent.executive;

/**
 * Business lens mapping to executive personas (plan § How executives think).
 */
public enum InsightLens {
    GROWTH("Growth (Sales)", "Sales"),
    RISK("Risk (Product)", "Product"),
    EFFICIENCY("Efficiency (Finance)", "Finance"),
    CUSTOMER("Customer (Product)", "Product"),
    GENERAL("General Discovery (Finance)", "Finance"),
    REVENUE("Revenue Model (Finance)", "Finance");

    private final String agentLabel;
    private final String defaultOwner;

    InsightLens(String agentLabel, String defaultOwner) {
        this.agentLabel = agentLabel;
        this.defaultOwner = defaultOwner;
    }

    public String agentLabel() { return agentLabel; }
    public String defaultOwner() { return defaultOwner; }
}
