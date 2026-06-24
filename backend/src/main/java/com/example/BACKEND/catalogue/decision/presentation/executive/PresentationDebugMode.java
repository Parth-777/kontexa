package com.example.BACKEND.catalogue.decision.presentation.executive;

import java.util.Map;

/**
 * Gates exposure of internal analytical metadata in API responses.
 */
public final class PresentationDebugMode {

    private PresentationDebugMode() {}

    public static boolean enabled(Map<String, Object> requestMeta) {
        if (requestMeta == null || requestMeta.isEmpty()) return false;
        Object v = requestMeta.get("presentationDebug");
        if (v == null) v = requestMeta.get("debugMode");
        if (v instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(v));
    }
}
