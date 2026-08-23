package com.example.BACKEND.tenancy;

import com.example.BACKEND.catalogue.decision.api.DecisionController;
import com.example.BACKEND.catalogue.decision.api.DecisionResponseMapper;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ExecutionRun;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ExecutionStage;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ExecutionState;
import com.example.BACKEND.catalogue.decision.runtime.DecisionRuntime;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanningProperties;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticShadowResponseMapper;
import com.example.BACKEND.identity.auth.AuthorizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionRunStatusOwnershipTest {

    @Mock DecisionRuntime runtime;
    @Mock DecisionResponseMapper responseMapper;
    @Mock SemanticPlanningProperties plannerProperties;
    @Mock SemanticShadowResponseMapper shadowResponseMapper;
    private final AuthorizationService authorization = new AuthorizationService();

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void runStatusDeniesCrossTenantRunId() {
        DecisionController controller = new DecisionController(
                runtime, responseMapper, plannerProperties, shadowResponseMapper, authorization);

        UUID runId = UUID.randomUUID();
        ExecutionRun foreignRun = new ExecutionRun(
                runId, "other-tenant", ExecutionState.COMPLETED, ExecutionStage.COMPLETED,
                Instant.now(), Instant.now(), null);

        when(runtime.status(runId)).thenReturn(foreignRun);

        TenantContextHolder.set(new TenantContext(
                UUID.randomUUID(), "acme", UUID.randomUUID(), "a@acme.com", "Ada",
                TenantRole.ANALYST, UUID.randomUUID(), "PASSWORD"));

        ResponseEntity<?> response = controller.runStatus(runId.toString());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void runStatusAllowsOwnedRun() {
        DecisionController controller = new DecisionController(
                runtime, responseMapper, plannerProperties, shadowResponseMapper, authorization);

        UUID runId = UUID.randomUUID();
        ExecutionRun owned = new ExecutionRun(
                runId, "acme", ExecutionState.COMPLETED, ExecutionStage.COMPLETED,
                Instant.now(), Instant.now(), null);

        when(runtime.status(runId)).thenReturn(owned);
        when(runtime.executionTrace(runId)).thenReturn(null);

        TenantContextHolder.set(new TenantContext(
                UUID.randomUUID(), "acme", UUID.randomUUID(), "a@acme.com", "Ada",
                TenantRole.ANALYST, UUID.randomUUID(), "PASSWORD"));

        ResponseEntity<?> response = controller.runStatus(runId.toString());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
