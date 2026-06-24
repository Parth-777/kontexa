package com.example.BACKEND.experiment.phase1;

public record Phase1PlannerInput(
        String question,
        Phase1CatalogueSnapshot catalogue,
        Phase1SchemaSnapshot schema
) {}
