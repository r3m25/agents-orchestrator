package com.agents.orchestrator;

import static org.junit.jupiter.api.Assertions.*;

import com.agents.orchestrator.infrastructure.process.BoundedProcess;
import com.agents.orchestrator.infrastructure.run.FileRunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.model.bpmn.Bpmn;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InfrastructureTest {
    @TempDir Path directory;

    @Test
    void storesAndReloadsResult() throws Exception {
        var store = new FileRunStore(directory, new ObjectMapper());
        assertNull(store.load("123"));
        store.save("123", Map.of("status", "BLOCKED"));
        assertEquals("BLOCKED", store.load("123").get("status"));
    }

    @Test
    void rejectsRunPathTraversal() {
        var store = new FileRunStore(directory, new ObjectMapper());
        assertThrows(IllegalArgumentException.class, () -> store.load("../config"));
    }

    @Test
    void terminatesProcessAfterTimeout() {
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () ->
                        assertThrows(
                                IllegalStateException.class,
                                () ->
                                        new BoundedProcess()
                                                .run(
                                                        List.of("/bin/sleep", "1"),
                                                        directory,
                                                        Duration.ofMillis(100),
                                                        100,
                                                        Map.of())));
    }

    @Test
    void rejectsExcessiveProcessOutput() {
        assertThrows(
                Exception.class,
                () ->
                        new BoundedProcess()
                                .run(
                                        List.of("/usr/bin/yes"),
                                        directory,
                                        Duration.ofSeconds(2),
                                        100,
                                        Map.of()));
    }

    @Test
    void bpmnIsValidAndKeepsIndependentProcessIdentity() {
        var model = Bpmn.readModelFromFile(Path.of("bpmn/agents-orchestrator.bpmn").toFile());
        Bpmn.validateModel(model);
        assertNotNull(model.getModelElementById("implementar_feature_desde_tech_lead_agent_v1"));
        assertNull(model.getModelElementById("Activity_CreateBranch"));
    }
}
