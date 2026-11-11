package com.agents.orchestrator.infrastructure.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpLocalCamundaClientTest {
    @TempDir Path directory;

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final List<RecordedRequest> requests = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void rejectsNonLocalCamundaAddress() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new HttpLocalCamundaClient(
                                json,
                                HttpClient.newHttpClient(),
                                URI.create("https://camunda.example")));
    }

    @Test
    void deploysBpmnAsMultipartResource() throws Exception {
        var bpmn = directory.resolve("agents-orchestrator.bpmn");
        Files.writeString(bpmn, "<definitions />");

        client().deploy(bpmn);

        var request = requests.getFirst();
        assertEquals("POST", request.method());
        assertEquals("/v2/deployments", request.path());
        assertTrue(request.contentType().startsWith("multipart/form-data; boundary="));
        assertTrue(request.body().contains("filename=\"agents-orchestrator.bpmn\""));
        assertTrue(request.body().contains("<definitions />"));
    }

    @Test
    void startsSynchronouslyWithImpactAnalysisRequest() throws Exception {
        var result = client().start("process-id", "sample-project", "SAMPLE-34", false);

        assertEquals(
                "BLOCKED", result.path("variables").path("impactAnalysis").path("status").asText());
        var body = json.readTree(requests.getFirst().body());
        assertEquals("POST", requests.getFirst().method());
        assertEquals("process-id", body.path("processDefinitionId").asText());
        assertEquals("sample-project", body.path("variables").path("projectId").asText());
        assertEquals("SAMPLE-34", body.path("variables").path("ticketKey").asText());
        assertTrue(body.path("awaitCompletion").asBoolean());
        assertEquals(600_000, body.path("requestTimeout").asInt());
        assertEquals("impactAnalysis", body.path("fetchVariables").get(0).asText());
    }

    @Test
    void startsAsynchronouslyWithoutCompletionFields() throws Exception {
        client().start("process-id", "sample-project", "SAMPLE-34", true);

        var body = json.readTree(requests.getFirst().body());
        assertFalse(body.has("awaitCompletion"));
        assertFalse(body.has("requestTimeout"));
        assertFalse(body.has("fetchVariables"));
    }

    @Test
    void inspectsProcessAndLoadsImpactAnalysisVariable() throws Exception {
        var result = client().inspect("2251799813768889");

        assertEquals(
                "2251799813768889", result.path("process").path("processInstanceKey").asText());
        assertEquals("PLANNED", result.path("analysis").get(0).path("status").asText());
        assertEquals(
                List.of("GET", "POST", "GET"),
                requests.stream().map(RecordedRequest::method).toList());
        assertEquals("/v2/process-instances/2251799813768889", requests.get(0).path());
        assertEquals("/v2/variables/search", requests.get(1).path());
        assertEquals("/v2/variables/123", requests.get(2).path());
    }

    private HttpLocalCamundaClient client() {
        return new HttpLocalCamundaClient(
                json,
                HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private void respond(HttpExchange exchange) throws IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(
                new RecordedRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        body));
        var response = responseFor(exchange.getRequestURI().getPath());
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private byte[] responseFor(String path) {
        var response =
                switch (path) {
                    case "/v2/process-instances/2251799813768889" ->
                            "{\"processInstanceKey\":\"2251799813768889\"}";
                    case "/v2/variables/search" -> "{\"items\":[{\"variableKey\":\"123\"}]}";
                    case "/v2/variables/123" -> "{\"value\":\"{\\\"status\\\":\\\"PLANNED\\\"}\"}";
                    default ->
                            "{\"processInstanceKey\":\"123\",\"variables\":{\"impactAnalysis\":{\"status\":\"BLOCKED\"}}}";
                };
        return response.getBytes(StandardCharsets.UTF_8);
    }

    private record RecordedRequest(String method, String path, String contentType, String body) {}
}
