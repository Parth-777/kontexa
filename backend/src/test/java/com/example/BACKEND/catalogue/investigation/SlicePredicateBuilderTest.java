package com.example.BACKEND.catalogue.investigation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SlicePredicateBuilderTest {

  private final SlicePredicateBuilder builder = new SlicePredicateBuilder();

  @Test
  void partitionSliceBuildsFilterPredicate() {
    var focal = new InvestigationObjective.Slice(List.of(
        new InvestigationObjective.Constraint(
            new InvestigationObjective.Filter("payment_type", "=", "1"),
            null, null, null)));
    var built = builder.build(
        focal,
        List.of(),
        "trips",
        new InvestigationObjective.Measure("tip_amount", "AVG", List.of()));
    assertTrue(built.sqlPredicate().contains("payment_type = 1"));
  }

  @Test
  void isNotNullFilterOmitsValueLiteral() {
    var slice = new InvestigationObjective.Slice(List.of(
        new InvestigationObjective.Constraint(
            new InvestigationObjective.Filter("customer_id", "IS NOT NULL", ""),
            null, null, null)));
    var built = builder.build(
        slice,
        List.of(),
        "trips",
        new InvestigationObjective.Measure("total_amount", "SUM", List.of()));
    assertTrue(built.sqlPredicate().contains("customer_id IS NOT NULL"));
    assertTrue(!built.sqlPredicate().contains("IS NOT NULL ''"));
  }
}
