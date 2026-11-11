package dev.orchestrator.techlead;

import dev.orchestrator.techlead.application.planning.PlanningContext;
import dev.orchestrator.techlead.infrastructure.codex.CodexPlanningEngine;
import dev.orchestrator.techlead.infrastructure.process.BoundedProcess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CodexPlanningEngineTest {
    @TempDir Path runtime;
    @Mock BoundedProcess process;
    final PlanningContext context = new PlanningContext("sample-project", "SAMPLE-1", Map.of("description", "Untrusted ticket"), List.of(), List.of());

    @ParameterizedTest
    @CsvSource({"sample-project,SAMPLE-1", "parking-app,PARK-1"})
    void invokesCodexForRequestedProjectWithReadOnlyPolicy(String projectId, String ticketKey) throws Exception {
        var context = new PlanningContext(projectId, ticketKey, Map.of("description", "Untrusted ticket"), List.of(), List.of());
        when(process.runWithInput(anyList(), any(), any(), anyInt(), anyMap(), anyString())).thenAnswer(call -> {
            List<String> args = call.getArgument(0);
            Map<String, String> env = call.getArgument(4);
            String input = call.getArgument(5);
            assertTrue(args.contains("read-only"));
            assertTrue(args.contains("features.shell_tool=false"));
            assertTrue(args.contains("--ignore-user-config"));
            assertEquals("-", args.getLast());
            assertFalse(env.containsKey("JIRA_API_TOKEN"));
            assertFalse(env.containsKey("OPENAI_API_KEY"));
            assertTrue(input.contains("Untrusted ticket"));
            assertTrue(input.contains("\"projectId\":\"" + projectId + "\""));
            assertTrue(input.contains("\"ticketKey\":\"" + ticketKey + "\""));
            Files.writeString(Path.of(args.get(args.indexOf("--output-last-message") + 1)), "{\"status\":\"BLOCKED\"}");
            return "";
        });
        var engine = new CodexPlanningEngine(new ObjectMapper(), process, "codex", runtime);

        assertEquals("{\"status\":\"BLOCKED\"}", engine.plan(context));

        try (var children = Files.list(runtime)) { assertEquals(0, children.count()); }
    }
    @Test void propagatesCliFailureAndCleansTemporaryFiles() throws Exception {
        when(process.runWithInput(anyList(), any(), any(), anyInt(), anyMap(), anyString()))
                .thenThrow(new IllegalStateException("PROCESS_TIMEOUT"));
        var engine = new CodexPlanningEngine(new ObjectMapper(), process, "codex", runtime);

        assertThrows(IllegalStateException.class, () -> engine.plan(context));

        try (var children = Files.list(runtime)) { assertEquals(0, children.count()); }
    }
    @Test void rejectsMissingCliOutput() throws Exception {
        when(process.runWithInput(anyList(), any(), any(), anyInt(), anyMap(), anyString())).thenReturn("");
        var engine = new CodexPlanningEngine(new ObjectMapper(), process, "codex", runtime);
        assertThrows(IllegalArgumentException.class, () -> engine.plan(context));
    }
}
