package com.example.BACKEND.catalogue.decision.semantics.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricAliasGeneratorTest {

    @Test
    void profitMarginAliasesIncludeProfitabilitySynonyms() {
        List<String> aliases = MetricAliasGenerator.aliasesFor("profit_margin", "profit margin");
        assertTrue(aliases.contains("profit"));
        assertTrue(aliases.contains("profitability"));
        assertTrue(aliases.contains("profitable"));
        assertTrue(aliases.contains("margin"));
    }

    @Test
    void revenueAliasesIncludeBusinessSynonyms() {
        List<String> aliases = MetricAliasGenerator.aliasesFor("total_revenue", "total revenue");
        assertTrue(aliases.contains("revenue"));
        assertTrue(aliases.contains("sales"));
        assertTrue(aliases.contains("income"));
        assertTrue(aliases.contains("earnings"));
    }

    @Test
    void stemsProfitabilityToProfit() {
        assertEquals("profit", MetricAliasGenerator.stemToken("profitability"));
        assertEquals("profit", MetricAliasGenerator.stemToken("profitable"));
    }
}
