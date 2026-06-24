package com.example.BACKEND.catalogue.decision.presentation.executive;

import com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse;
import com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse.FindingItem;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Next Steps / prescriptive content only when analytically warranted.
 */
@Component
public class PrescriptiveContentGate {

    public static final double MIN_CONFIDENCE = 0.85;

    public boolean allowPrescriptiveContent(AnalyticalResponse analytical, double confidence) {
        if (analytical == null || analytical.recoveryMode()) return false;
        if (confidence < MIN_CONFIDENCE) return false;
        return hasAnomaly(analytical) || hasMeaningfulShift(analytical);
    }

    private boolean hasAnomaly(AnalyticalResponse analytical) {
        if (analytical.findings() == null) return false;
        for (FindingItem f : analytical.findings()) {
            if (f == null || f.type() == null) continue;
            String type = f.type().toLowerCase(Locale.ROOT);
            if (type.contains("anomal")) return true;
        }
        return false;
    }

    private boolean hasMeaningfulShift(AnalyticalResponse analytical) {
        if (analytical.findings() == null) return false;
        for (FindingItem f : analytical.findings()) {
            if (f == null) continue;
            String type = f.type() != null ? f.type().toLowerCase(Locale.ROOT) : "";
            String label = f.label() != null ? f.label().toLowerCase(Locale.ROOT) : "";
            String summary = f.summary() != null ? f.summary().toLowerCase(Locale.ROOT) : "";
            if (type.contains("anomal") || type.contains("temporal")
                    || label.contains("shift") || label.contains("anomal")
                    || summary.contains("accelerat") || summary.contains("decelerat")
                    || summary.contains("swing")) {
                return true;
            }
        }
        return false;
    }
}
