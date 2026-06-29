package com.example.BACKEND.catalogue.decision.investigation;

import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Selects the catalogue dimensions eligible for contribution decomposition.
 *
 * <p>Eligibility is purely structural: categorical dimension columns from the approved
 * catalogue, excluding the time column and the measure column. No keyword or question-text
 * heuristics are applied.
 */
@Component
public class CandidateDimensionEnumerator {

    public List<CandidateDimension> enumerate(
            ApprovedCatalogueSnapshot catalogue,
            String timeColumn,
            String measureColumn,
            int maxDimensions
    ) {
        List<CandidateDimension> out = new ArrayList<>();
        if (catalogue == null) {
            return out;
        }
        for (ApprovedCatalogueSnapshot.CatalogueColumn col : catalogue.columns()) {
            if (out.size() >= maxDimensions) {
                break;
            }
            String role = col.role() != null ? col.role().toLowerCase(Locale.ROOT) : "";
            boolean isCategorical = role.contains("dimension") || role.contains("identifier");
            if (!isCategorical) {
                continue;
            }
            if (equalsCol(col.columnName(), timeColumn) || equalsCol(col.columnName(), measureColumn)) {
                continue;
            }
            out.add(new CandidateDimension(
                    col.columnName(),
                    humanize(col.columnName()),
                    "catalogue categorical dimension"));
        }
        return out;
    }

    private static boolean equalsCol(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static String humanize(String col) {
        return col != null ? col.replace('_', ' ') : "";
    }
}
