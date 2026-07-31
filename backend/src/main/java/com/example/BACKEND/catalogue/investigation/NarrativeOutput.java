package com.example.BACKEND.catalogue.investigation;

import java.util.List;

/**
 * Phase 2 (Investigation Runtime V1) contract — pure data, no behavior.
 *
 * <p>The synthesized "why" explanation derived (in a later step) strictly from an
 * {@link EvidencePack}. Used to render the investigation answer in the chat UI.
 *
 * <p>This is a contract only. It contains no execution logic and no Phase 1 imports.
 */
public record NarrativeOutput(
        String headline,
        String explanation,
        List<String> keyFindings,
        String confidenceLevel
) {}
