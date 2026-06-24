package com.example.BACKEND.catalogue.decision.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects method-entry events from bytecode instrumentation during diagnostic runs.
 */
public final class RuntimeDependencyInvocationSink {

    public record Invocation(String className, String method, List<String> stack) {}

    private static final Map<String, AtomicInteger> COUNTS = new LinkedHashMap<>();
    private static final Map<String, List<Invocation>> SAMPLES = new LinkedHashMap<>();

    private RuntimeDependencyInvocationSink() {}

    public static void reset() {
        COUNTS.clear();
        SAMPLES.clear();
    }

    public static void record(String className, String method) {
        COUNTS.computeIfAbsent(className, k -> new AtomicInteger()).incrementAndGet();
        SAMPLES.computeIfAbsent(className, k -> new ArrayList<>());
        List<Invocation> list = SAMPLES.get(className);
        if (list.size() < 3) {
            list.add(new Invocation(className, method, captureStack(className)));
        }
    }

    public static int count(String className) {
        AtomicInteger c = COUNTS.get(className);
        return c == null ? 0 : c.get();
    }

    public static List<Invocation> samples(String className) {
        return SAMPLES.getOrDefault(className, List.of());
    }

    public static Map<String, Integer> allCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        COUNTS.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    private static List<String> captureStack(String className) {
        List<String> frames = new ArrayList<>();
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String cn = frame.getClassName();
            if (cn.contains("RuntimeDependency") || cn.contains("TracingDomain")
                    || cn.contains("bytebuddy") || cn.contains("junit")) {
                continue;
            }
            String line = cn + "." + frame.getMethodName()
                    + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")";
            frames.add(line);
            if (frames.size() >= 10) break;
        }
        return Collections.unmodifiableList(frames);
    }
}
