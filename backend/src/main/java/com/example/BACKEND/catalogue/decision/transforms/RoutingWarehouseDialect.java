package com.example.BACKEND.catalogue.decision.transforms;

import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.example.BACKEND.tenancy.TenantContext;
import com.example.BACKEND.tenancy.TenantContextHolder;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Resolves the active {@link WarehouseDialect} from the existing getProvider() contract.
 * DecisionRuntime / planners do not branch on warehouse here.
 */
@Component
@Primary
public class RoutingWarehouseDialect implements WarehouseDialect {

    private final BigQueryDialect bigQueryDialect;
    private final SnowflakeDialect snowflakeDialect;
    private final TenantCloudConnectionService connections;

    public RoutingWarehouseDialect(
            BigQueryDialect bigQueryDialect,
            SnowflakeDialect snowflakeDialect,
            TenantCloudConnectionService connections
    ) {
        this.bigQueryDialect = bigQueryDialect;
        this.snowflakeDialect = snowflakeDialect;
        this.connections = connections;
    }

    WarehouseDialect active() {
        TenantContext ctx = TenantContextHolder.get();
        if (ctx == null) {
            return bigQueryDialect;
        }
        String provider = connections.getProvider(ctx.tenantId());
        if (provider == null || provider.isBlank()) {
            provider = connections.getProvider(ctx.legacyTenantKey());
        }
        if ("snowflake".equalsIgnoreCase(provider)) {
            return snowflakeDialect;
        }
        return bigQueryDialect;
    }

    @Override
    public String extractHour(String timestampColumn) {
        return active().extractHour(timestampColumn);
    }

    @Override
    public String extractDayOfWeek(String timestampColumn) {
        return active().extractDayOfWeek(timestampColumn);
    }

    @Override
    public String extractWeek(String timestampColumn) {
        return active().extractWeek(timestampColumn);
    }

    @Override
    public String extractMonth(String timestampColumn) {
        return active().extractMonth(timestampColumn);
    }

    @Override
    public String extractQuarter(String timestampColumn) {
        return active().extractQuarter(timestampColumn);
    }

    @Override
    public String extractYear(String timestampColumn) {
        return active().extractYear(timestampColumn);
    }

    @Override
    public String weekendDayType(String timestampColumn) {
        return active().weekendDayType(timestampColumn);
    }

    @Override
    public String weekdayName(String timestampColumn) {
        return active().weekdayName(timestampColumn);
    }

    @Override
    public String quoteIdent(String ident) {
        return active().quoteIdent(ident);
    }

    @Override
    public String timestampTrunc(String timestampColumn, String grain) {
        return active().timestampTrunc(timestampColumn, grain);
    }

    @Override
    public String safeDivide(String numeratorSql, String denominatorSql) {
        return active().safeDivide(numeratorSql, denominatorSql);
    }

    @Override
    public String countIf(String conditionSql) {
        return active().countIf(conditionSql);
    }
}
