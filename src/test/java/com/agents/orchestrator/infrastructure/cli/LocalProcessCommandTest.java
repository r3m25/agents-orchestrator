package com.agents.orchestrator.infrastructure.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LocalProcessCommandTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void startsSynchronouslyAndPrintsImpactAnalysis() throws Exception {
        var client =
                new RecordingClient(
                        json.readTree(
                                "{\"processInstanceKey\":\"123\",\"variables\":{\"impactAnalysis\":{\"status\":\"BLOCKED\"}}}"));
        var output = output();

        LocalProcessCommand.execute(
                new String[] {"start", "--project", "sample-project", "--ticket", "SAMPLE-34"},
                client,
                Path.of("ignored.bpmn"),
                output.stream(),
                json);

        assertEquals("implementar_feature_desde_tech_lead_agent_v1", client.processDefinitionId);
        assertEquals("sample-project", client.projectId);
        assertEquals("SAMPLE-34", client.ticketKey);
        assertFalse(client.asynchronous);
        assertEquals(
                "BLOCKED",
                json.readTree(output.value())
                        .path("variables")
                        .path("impactAnalysis")
                        .path("status")
                        .asText());
    }

    @Test
    void startsAsynchronouslyWhenRequested() throws Exception {
        var client =
                new RecordingClient(
                        json.readTree("{\"processInstanceKey\":\"123\",\"variables\":{}}"));

        LocalProcessCommand.execute(
                new String[] {
                    "start", "--project", "sample-project", "--ticket", "SAMPLE-34", "--async"
                },
                client,
                Path.of("ignored.bpmn"),
                output().stream(),
                json);

        assertEquals("sample-project", client.projectId);
        assertEquals("SAMPLE-34", client.ticketKey);
        assertEquals(true, client.asynchronous);
    }

    @Test
    void rejectsSynchronousCompletionWithoutAnalysis() throws Exception {
        var client = new RecordingClient(json.readTree("{\"variables\":{}}"));

        assertThrows(
                IllegalStateException.class,
                () ->
                        LocalProcessCommand.execute(
                                new String[] {
                                    "start", "--project", "sample-project", "--ticket", "SAMPLE-34"
                                },
                                client,
                                Path.of("ignored.bpmn"),
                                output().stream(),
                                json));
    }

    @Test
    void rejectsStartWithoutValidProjectBeforeCallingClient() {
        var client = new RecordingClient(json.createObjectNode());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        LocalProcessCommand.execute(
                                new String[] {"start", "--ticket", "SAMPLE-34"},
                                client,
                                Path.of("ignored.bpmn"),
                                output().stream(),
                                json));

        assertEquals(null, client.projectId);
    }

    @Test
    void inspectsTheRequestedInstance() throws Exception {
        var client = new RecordingClient(json.createObjectNode());
        client.inspection =
                json.readTree("{\"process\":{},\"analysis\":[{\"status\":\"PLANNED\"}]} ");

        LocalProcessCommand.execute(
                new String[] {"inspect", "--instance", "2251799813768889"},
                client,
                Path.of("ignored.bpmn"),
                output().stream(),
                json);

        assertEquals("2251799813768889", client.instanceKey);
    }

    @Test
    void deploysThePackagedBpmn() throws Exception {
        var client = new RecordingClient(json.createObjectNode());
        var bpmn = Path.of("bpmn", "agents-orchestrator.bpmn");

        LocalProcessCommand.execute(new String[] {"deploy"}, client, bpmn, output().stream(), json);

        assertEquals(bpmn, client.deployedBpmn);
    }

    private Output output() {
        return new Output();
    }

    private static final class Output {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        PrintStream stream() {
            return new PrintStream(bytes);
        }

        String value() {
            return bytes.toString();
        }
    }

    private static final class RecordingClient implements LocalCamundaClient {
        private final JsonNode startResult;
        private Path deployedBpmn;
        private String processDefinitionId;
        private String projectId;
        private String ticketKey;
        private boolean asynchronous;
        private String instanceKey;
        private JsonNode inspection;

        private RecordingClient(JsonNode startResult) {
            this.startResult = startResult;
        }

        @Override
        public JsonNode deploy(Path bpmn) {
            deployedBpmn = bpmn;
            return startResult;
        }

        @Override
        public JsonNode start(String processId, String project, String ticket, boolean async) {
            processDefinitionId = processId;
            projectId = project;
            ticketKey = ticket;
            asynchronous = async;
            return startResult;
        }

        @Override
        public JsonNode inspect(String instance) {
            instanceKey = instance;
            return inspection;
        }
    }
}
