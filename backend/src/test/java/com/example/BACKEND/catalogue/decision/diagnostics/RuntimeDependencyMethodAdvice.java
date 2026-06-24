package com.example.BACKEND.catalogue.decision.diagnostics;

import net.bytebuddy.asm.Advice;

/**
 * Injected at method entry for traced production classes.
 */
public final class RuntimeDependencyMethodAdvice {

    private RuntimeDependencyMethodAdvice() {}

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Origin("#t") String typeName) {
        RuntimeDependencyInvocationSink.record(typeName, callerMethodName());
    }

    /** Must be public — inlined into instrumented bytecode. */
    public static String callerMethodName() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        boolean seenAdvice = false;
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().equals(RuntimeDependencyMethodAdvice.class.getName())) {
                seenAdvice = true;
                continue;
            }
            if (!seenAdvice) continue;
            if (frame.getClassName().contains("bytebuddy")) continue;
            return frame.getMethodName();
        }
        return "unknown";
    }
}
