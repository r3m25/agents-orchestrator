package com.agents.orchestrator.infrastructure.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

final class HttpLocalCamundaClient implements LocalCamundaClient {
    private final ObjectMapper json;
    private final HttpClient client;
    private final URI baseUrl;

    HttpLocalCamundaClient(ObjectMapper json, HttpClient client, URI baseUrl) {
        if (baseUrl == null
                || !("localhost".equals(baseUrl.getHost())
                        || "127.0.0.1".equals(baseUrl.getHost()))) {
            throw new IllegalArgumentException("ONLY_LOCAL_CAMUNDA_SUPPORTED");
        }
        this.json = json;
        this.client = client;
        this.baseUrl = baseUrl.toString().endsWith("/") ? baseUrl : URI.create(baseUrl + "/");
    }

    @Override
    public JsonNode deploy(Path bpmn) throws Exception {
        var boundary = "AgentsOrchestratorBoundary";
        var body =
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\"resources\"; filename=\""
                                + bpmn.getFileName()
                                + "\"\r\nContent-Type: application/xml\r\n\r\n")
                        .getBytes();
        var closing = ("\r\n--" + boundary + "--\r\n").getBytes();
        var contents = Files.readAllBytes(bpmn);
        var payload = new byte[body.length + contents.length + closing.length];
        System.arraycopy(body, 0, payload, 0, body.length);
        System.arraycopy(contents, 0, payload, body.length, contents.length);
        System.arraycopy(closing, 0, payload, body.length + contents.length, closing.length);
        return request(
                "deployments",
                HttpRequest.BodyPublishers.ofByteArray(payload),
                "multipart/form-data; boundary=" + boundary,
                Duration.ofSeconds(30));
    }

    @Override
    public JsonNode start(
            String processDefinitionId, String projectId, String ticketKey, boolean asynchronous)
            throws Exception {
        var payload = json.createObjectNode().put("processDefinitionId", processDefinitionId);
        payload.set(
                "variables",
                json.createObjectNode().put("projectId", projectId).put("ticketKey", ticketKey));
        if (!asynchronous) {
            payload.put("awaitCompletion", true);
            payload.put("requestTimeout", 600_000);
            payload.set("fetchVariables", json.createArrayNode().add("impactAnalysis"));
        }
        return request(
                "process-instances",
                HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)),
                "application/json",
                asynchronous ? Duration.ofSeconds(30) : Duration.ofSeconds(630));
    }

    @Override
    public JsonNode inspect(String instanceKey) throws Exception {
        var result = json.createObjectNode();
        result.set(
                "process",
                request("process-instances/" + instanceKey, null, null, Duration.ofSeconds(30)));
        var filter =
                json.createObjectNode()
                        .set(
                                "filter",
                                json.createObjectNode()
                                        .put("processInstanceKey", instanceKey)
                                        .put("name", "impactAnalysis"));
        var variables =
                request(
                        "variables/search",
                        HttpRequest.BodyPublishers.ofString(json.writeValueAsString(filter)),
                        "application/json",
                        Duration.ofSeconds(30));
        var analysis = result.putArray("analysis");
        for (var variable : variables.path("items")) {
            var full =
                    request(
                            "variables/" + variable.path("variableKey").asText(),
                            null,
                            null,
                            Duration.ofSeconds(30));
            analysis.add(json.readTree(full.path("value").asText()));
        }
        return result;
    }

    private JsonNode request(
            String path, HttpRequest.BodyPublisher body, String contentType, Duration timeout)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(baseUrl.resolve("v2/" + path))
                        .timeout(timeout)
                        .header("Accept", "application/json");
        if (contentType != null) builder.header("Content-Type", contentType);
        var request = body == null ? builder.GET().build() : builder.POST(body).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("CAMUNDA_HTTP_" + response.statusCode());
        }
        return json.readTree(response.body());
    }
}
