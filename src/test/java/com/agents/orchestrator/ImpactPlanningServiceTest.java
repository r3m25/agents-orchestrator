package com.agents.orchestrator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.agents.orchestrator.application.planning.ContextSource;
import com.agents.orchestrator.application.planning.ImpactPlanningService;
import com.agents.orchestrator.application.planning.PlanContract;
import com.agents.orchestrator.application.planning.PlanningContext;
import com.agents.orchestrator.application.planning.PlanningEngine;
import com.agents.orchestrator.application.run.RunStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImpactPlanningServiceTest {
    @Mock ContextSource source;
    @Mock PlanningEngine engine;
    @Mock PlanContract contract;
    @Mock RunStore store;
    @InjectMocks ImpactPlanningService service;

    @Test
    void persistsValidatedResultBeforeReturning() throws Exception {
        var variables = Map.<String, Object>of("ticketKey", "SAMPLE-1");
        var context =
                new PlanningContext("sample-project", "SAMPLE-1", Map.of(), List.of(), List.of());
        when(store.load("123")).thenReturn(null);
        when(source.load(variables)).thenReturn(context);
        when(engine.plan(context)).thenReturn("json");
        when(engine.name()).thenReturn("codex-cli");
        when(contract.validate("json", context)).thenReturn(Map.of("status", "PLANNED"));

        var result = service.analyze("123", variables);

        assertEquals(true, result.get("readOnly"));
        assertEquals("123", result.get("runId"));
        verify(store).save("123", result);
    }

    @Test
    void reusesPersistedResultOnJobRedelivery() throws Exception {
        var cached = Map.<String, Object>of("status", "PLANNED");
        when(store.load("123")).thenReturn(cached);

        assertEquals(cached, service.analyze("123", Map.of()));

        verifyNoInteractions(source, engine, contract);
        verify(store, never()).save(anyString(), anyMap());
    }

    @Test
    void doesNotPersistInvalidModelResponse() throws Exception {
        var context =
                new PlanningContext("sample-project", "SAMPLE-1", Map.of(), List.of(), List.of());
        when(store.load("123")).thenReturn(null);
        when(source.load(Map.of())).thenReturn(context);
        when(engine.plan(context)).thenReturn("invalid");
        when(contract.validate("invalid", context))
                .thenThrow(new IllegalArgumentException("INVALID_PLAN"));

        assertThrows(IllegalArgumentException.class, () -> service.analyze("123", Map.of()));

        verify(store, never()).save(anyString(), anyMap());
    }
}
