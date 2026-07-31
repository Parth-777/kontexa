package com.example.BACKEND.catalogue.investigation;

/**
 * SQL predicate for a resolved slice plus a human-readable label.
 */
public record SlicePredicate(String sqlPredicate, String label) {
    public static final SlicePredicate POPULATION = new SlicePredicate("TRUE", "population");
}
