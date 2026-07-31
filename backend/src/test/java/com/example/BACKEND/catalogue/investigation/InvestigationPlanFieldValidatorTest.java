package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot.CatalogueColumn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestigationPlanFieldValidatorTest {

    private final InvestigationPlanFieldValidator validator = new InvestigationPlanFieldValidator();

    private static ApprovedCatalogueSnapshot gaCatalogue() {
        return new ApprovedCatalogueSnapshot(
                "ga_sessions",
                "project.dataset.ga_sessions",
                List.of(
                        new CatalogueColumn(
                                "geoNetwork", "container", "RECORD", "Geo dimensions", null, List.of(), true, null),
                        new CatalogueColumn(
                                "geoNetwork.country", "dimension", "STRING", "Country", null, List.of(), false, "geoNetwork"),
                        new CatalogueColumn(
                                "visitId", "metric", "INTEGER", "Session id", "COUNT", List.of(), false, null),
                        new CatalogueColumn(
                                "channelGrouping", "dimension", "STRING", "Channel", null, List.of(), false, null)));
    }

    @Test
    void recordParentDimensionRejected() {
        InvestigationPlan plan = planWithDimension("geoNetwork");
        assertFalse(validator.validate(plan, gaCatalogue()).valid());
    }

    @Test
    void catalogueLeafPathAllowed() {
        InvestigationPlan plan = planWithDimension("geoNetwork.country");
        assertTrue(validator.validate(plan, gaCatalogue()).valid());
    }

    @Test
    void unknownLeafPathRejected() {
        InvestigationPlan plan = planWithDimension("geoNetwork.city");
        assertFalse(validator.validate(plan, gaCatalogue()).valid());
    }

    @Test
    void flatScalarDimensionAllowed() {
        InvestigationPlan plan = new InvestigationPlan(
                new InvestigationPlan.Phenomenon(
                        "regions",
                        new InvestigationObjective.Measure("tip_amount", "AVG", List.of())),
                List.of(),
                List.of(new InvestigationPlan.EvidenceRequest(
                        "1", "metric_by_dimension", null,
                        new InvestigationObjective.Measure("tip_amount", "AVG", List.of()),
                        "region",
                        null, null, null, null)),
                0.9);
        ApprovedCatalogueSnapshot taxi = new ApprovedCatalogueSnapshot(
                "yellow_taxi_trips",
                "public.yellow_taxi_trips",
                List.of(
                        new CatalogueColumn("tip_amount", "metric", "FLOAT", "", "AVG", List.of()),
                        new CatalogueColumn("region", "dimension", "STRING", "", null, List.of())));
        assertTrue(validator.validate(plan, taxi).valid());
    }

    private static InvestigationPlan planWithDimension(String dimension) {
        return new InvestigationPlan(
                new InvestigationPlan.Phenomenon(
                        "countries",
                        new InvestigationObjective.Measure("visitId", "COUNT", List.of())),
                List.of(),
                List.of(new InvestigationPlan.EvidenceRequest(
                        "1", "metric_by_dimension", null,
                        new InvestigationObjective.Measure("visitId", "COUNT", List.of()),
                        dimension,
                        null, null, null, null)),
                0.9);
    }
}
