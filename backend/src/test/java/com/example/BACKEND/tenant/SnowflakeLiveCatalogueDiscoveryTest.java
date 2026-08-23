package com.example.BACKEND.tenant;

import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.service.SnowflakeCatalogueService;
import com.example.BACKEND.identity.IdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live Snowflake discovery against the saved workspace connector.
 * Skips when no Snowflake connector is configured (CI / machines without SF).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SnowflakeLiveCatalogueDiscoveryTest {

    @Autowired SnowflakeCatalogueService catalogueService;
    @Autowired TenantCloudConnectionService cloudConnections;
    @Autowired IdentityRepository identities;

    @Test
    void liveDiscoverUsesConfiguredWarehouseAndFindsPublicTables() {
        var workspaceId = identities.findWorkspaceIdBySlug("google-admin");
        assumeTrue(workspaceId.isPresent(), "workspace google-admin not present");

        var config = cloudConnections.getSnowflakeConfig(workspaceId.get());
        assumeTrue(config.isPresent(), "no Snowflake connector for google-admin");
        assumeTrue(config.get().password() != null && !config.get().password().isBlank(),
                "Snowflake password not stored");

        CatalogueResult result = catalogueService.discover(config.get(), false);

        Set<String> tables = result.getTables().stream()
                .map(t -> t.getTableName().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        assertTrue(tables.contains("CUSTOMERS"), "expected CUSTOMERS, found " + tables);
        assertTrue(tables.contains("PRODUCTS"), "expected PRODUCTS, found " + tables);
        assertTrue(tables.contains("ORDERS"), "expected ORDERS, found " + tables);
        assertFalse(result.getTables().stream().anyMatch(t -> t.getColumns().isEmpty()),
                "each discovered table should have columns");
    }
}
