package com.example.BACKEND.catalogue.decision.presentation.executive.strategy;

import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;

import java.util.List;
import java.util.Map;

public interface PresentationStrategy {

    PresentationStrategyType type();

    ExecutivePresentation build(
            CanonicalQueryModel model,
            List<Map<String, Object>> rows,
            PresentationBuildContext context
    );
}
