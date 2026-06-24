package com.example.BACKEND.catalogue.decision.diagnostics;

import com.example.BACKEND.catalogue.decision.planning.DomainAnalyticalDefaults;

import java.util.Map;

/**
 * Records every {@link DomainAnalyticalDefaults} method entry during diagnostic runs.
 */
public final class TracingDomainAnalyticalDefaults extends DomainAnalyticalDefaults {

    private static final String TYPE = DomainAnalyticalDefaults.class.getName();

    public TracingDomainAnalyticalDefaults() {
        record("<init>");
    }

    @Override
    public DomainProfile resolve(String question, Map<String, Object> meta) {
        record("resolve");
        return super.resolve(question, meta);
    }

    @Override
    public String resolveRevenueColumn(DomainProfile profile) {
        record("resolveRevenueColumn");
        return super.resolveRevenueColumn(profile);
    }

    @Override
    public boolean retentionSupported(DomainProfile profile,
                                      com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType intent) {
        record("retentionSupported");
        return super.retentionSupported(profile, intent);
    }

    private static void record(String method) {
        RuntimeDependencyInvocationSink.record(TYPE, method);
    }
}
