package com.example.BACKEND.catalogue.investigation;



import com.example.BACKEND.catalogue.schema.NestedSchemaFieldGraph;

import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;

import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot.CatalogueColumn;

import org.springframework.stereotype.Component;



import java.util.ArrayList;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;

import java.util.Optional;



/**

 * Pre-execution guard that blocks nested container fields (RECORD/STRUCT) from

 * reaching SQL compilation when the planner bound a non-leaf dimension or measure.

 */

@Component

public class InvestigationPlanFieldValidator {



    public record FieldIssue(

            String fieldRef,

            String role,

            String requestId,

            String code,

            String message

    ) {}



    public record Result(boolean valid, List<FieldIssue> issues) {

        public String summaryMessage() {

            if (valid || issues == null || issues.isEmpty()) {

                return "";

            }

            return issues.stream()

                    .map(FieldIssue::message)

                    .distinct()

                    .reduce((a, b) -> a + "; " + b)

                    .orElse("Investigation field validation failed");

        }



        public List<Map<String, Object>> toDiagnostics() {

            List<Map<String, Object>> out = new ArrayList<>();

            if (issues == null) {

                return out;

            }

            for (FieldIssue issue : issues) {

                Map<String, Object> row = new LinkedHashMap<>();

                row.put("field_ref", issue.fieldRef());

                row.put("role", issue.role());

                row.put("request_id", issue.requestId());

                row.put("code", issue.code());

                row.put("message", issue.message());

                out.add(row);

            }

            return out;

        }

    }



    public Result validate(InvestigationPlan plan, ApprovedCatalogueSnapshot catalogue) {

        List<FieldIssue> issues = new ArrayList<>();

        if (plan == null) {

            return new Result(true, List.of());

        }

        if (plan.phenomenon() != null && plan.phenomenon().measure() != null) {

            checkField(

                    plan.phenomenon().measure().column(),

                    "phenomenon.measure",

                    null,

                    catalogue,

                    issues);

        }

        if (plan.evidenceRequests() != null) {

            for (InvestigationPlan.EvidenceRequest request : plan.evidenceRequests()) {

                checkField(request.dimension(), "dimension", request.requestId(), catalogue, issues);

                if (request.measure() != null) {

                    checkField(

                            request.measure().column(),

                            "measure",

                            request.requestId(),

                            catalogue,

                            issues);

                }

            }

        }

        return new Result(issues.isEmpty(), List.copyOf(issues));

    }



    private void checkField(

            String fieldRef,

            String role,

            String requestId,

            ApprovedCatalogueSnapshot catalogue,

            List<FieldIssue> issues

    ) {

        if (fieldRef == null || fieldRef.isBlank() || catalogue == null) {

            return;

        }



        Optional<CatalogueColumn> exact = catalogue.findColumn(fieldRef);

        if (exact.isEmpty()) {

            issues.add(new FieldIssue(

                    fieldRef,

                    role,

                    requestId,

                    "UNKNOWN_CATALOGUE_FIELD",

                    "Field '" + fieldRef + "' (" + role + ") is not present in the approved catalogue."));

            return;

        }

        if (NestedSchemaFieldGraph.isContainer(exact.get())) {

            issues.add(nestedContainerIssue(fieldRef, role, requestId, exact.get().dataType()));

        }

    }



    private FieldIssue nestedContainerIssue(

            String fieldRef,

            String role,

            String requestId,

            String dataType

    ) {

        String message = "Field '" + fieldRef + "' (" + role + ") is a nested "

                + dataType + " container and cannot be grouped directly. "

                + "Bind an explicit catalogue leaf path such as "

                + fieldRef + ".<child>.";

        return new FieldIssue(fieldRef, role, requestId, "NESTED_CONTAINER_FIELD", message);

    }

}


