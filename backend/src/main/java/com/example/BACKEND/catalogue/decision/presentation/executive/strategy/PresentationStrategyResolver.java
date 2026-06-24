package com.example.BACKEND.catalogue.decision.presentation.executive.strategy;

import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Chooses presentation strategy from {@link CanonicalQueryModel} metadata intent and structure.
 * Never inspects the user question.
 */
@Component
public class PresentationStrategyResolver {

    public PresentationStrategyType resolve(CanonicalQueryModel model) {
        if (model == null) {
            return PresentationStrategyType.SCALAR;
        }

        String intent = metadataIntent(model);
        if ("GROWTH".equals(intent) && hasTimeGrain(model)) {
            return PresentationStrategyType.GROWTH;
        }
        if ("PARETO".equals(intent) && hasOrdering(model) && hasPartition(model)) {
            return PresentationStrategyType.PARETO;
        }
        if ("OUTLIER".equals(intent) && hasPartition(model)) {
            return PresentationStrategyType.OUTLIER;
        }
        if ("VARIANCE".equals(intent) && hasPartition(model)) {
            return PresentationStrategyType.VARIANCE;
        }

        if (model.bivariate() != null
                && model.bivariate().function() != null
                && !model.bivariate().function().isBlank()) {
            return PresentationStrategyType.CORRELATION;
        }
        if (model.ratio() != null) {
            String kind = model.ratio().kind() != null
                    ? model.ratio().kind().toUpperCase(Locale.ROOT) : "";
            if ("CONTRIBUTION".equals(kind)) {
                return PresentationStrategyType.CONTRIBUTION;
            }
            return PresentationStrategyType.COMPARISON;
        }
        if (hasOrdering(model)) {
            return PresentationStrategyType.RANKING;
        }
        if (hasTimeGrain(model)) {
            return PresentationStrategyType.TREND;
        }
        if (hasPartition(model)) {
            return PresentationStrategyType.DISTRIBUTION;
        }
        return PresentationStrategyType.SCALAR;
    }

    private static String metadataIntent(CanonicalQueryModel model) {
        if (model.metadata() == null || model.metadata().intent() == null) {
            return "";
        }
        return model.metadata().intent().toUpperCase(Locale.ROOT);
    }

    private static boolean hasPartition(CanonicalQueryModel model) {
        return model.partition() != null
                && model.partition().column() != null
                && !model.partition().column().isBlank();
    }

    private static boolean hasTimeGrain(CanonicalQueryModel model) {
        return model.partition() != null
                && model.partition().timeGrain() != null
                && !model.partition().timeGrain().isBlank();
    }

    private static boolean hasOrdering(CanonicalQueryModel model) {
        return model.ordering() != null
                && model.ordering().column() != null
                && !model.ordering().column().isBlank();
    }
}
