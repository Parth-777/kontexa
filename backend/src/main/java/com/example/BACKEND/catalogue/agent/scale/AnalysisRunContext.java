package com.example.BACKEND.catalogue.agent.scale;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-tenant analysis run budget (queries, bytes scanned).
 */
public class AnalysisRunContext {

    private final int maxQueries;
    private final long maxBytesPerRun;
    private final AtomicInteger queriesRun = new AtomicInteger();
    private final AtomicLong bytesScanned = new AtomicLong();
    private volatile boolean budgetExceeded;

    public AnalysisRunContext(int maxQueries, long maxBytesPerRun) {
        this.maxQueries = maxQueries;
        this.maxBytesPerRun = maxBytesPerRun;
    }

    public static AnalysisRunContext unlimited() {
        return new AnalysisRunContext(Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    public boolean canRunQuery() {
        return !budgetExceeded && queriesRun.get() < maxQueries;
    }

    public void recordQuery(long estimatedBytes) {
        queriesRun.incrementAndGet();
        if (estimatedBytes > 0) {
            long total = bytesScanned.addAndGet(estimatedBytes);
            if (total > maxBytesPerRun) budgetExceeded = true;
        }
        if (queriesRun.get() >= maxQueries) budgetExceeded = true;
    }

    public void markBudgetExceeded() {
        budgetExceeded = true;
    }

    public boolean isBudgetExceeded() {
        return budgetExceeded;
    }

    public int queriesRun() {
        return queriesRun.get();
    }

    public long bytesScanned() {
        return bytesScanned.get();
    }
}
