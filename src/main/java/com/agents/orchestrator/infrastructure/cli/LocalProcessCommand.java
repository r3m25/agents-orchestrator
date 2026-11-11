package com.agents.orchestrator.infrastructure.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Local Camunda development client. It is separate from the Spring worker runtime. */
public final class LocalProcessCommand {
    private static final String PROCESS_ID = "implementar_feature_desde_tech_lead_agent_v1";

    private LocalProcessCommand() {}

    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var address = System.getenv().getOrDefault("CAMUNDA_REST_ADDRESS", "http://127.0.0.1:8088");
        var client =
                new HttpLocalCamundaClient(json, HttpClient.newHttpClient(), URI.create(address));
        execute(args, client, Path.of("bpmn", "agents-orchestrator.bpmn"), System.out, json);
    }

    static void execute(
            String[] args,
            LocalCamundaClient client,
            Path bpmn,
            PrintStream output,
            ObjectMapper json)
            throws Exception {
        if (args.length == 0)
            throw new IllegalArgumentException("EXPECTED_ACTION_DEPLOY_START_OR_INSPECT");
        var options = parseOptions(Arrays.copyOfRange(args, 1, args.length));
        JsonNode result =
                switch (args[0]) {
                    case "deploy" -> requireNoOptions(options, client.deploy(bpmn));
                    case "start" -> start(options, client);
                    case "inspect" -> inspect(options, client);
                    default ->
                            throw new IllegalArgumentException(
                                    "EXPECTED_ACTION_DEPLOY_START_OR_INSPECT");
                };
        output.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private static JsonNode start(Map<String, String> options, LocalCamundaClient client)
            throws Exception {
        var projectId = options.remove("project");
        var ticketKey = options.remove("ticket");
        var asynchronous = options.remove("async") != null;
        if (!options.isEmpty()
                || projectId == null
                || !projectId.matches("[a-z0-9-]{1,64}")
                || ticketKey == null
                || !ticketKey.matches("[A-Z][A-Z0-9]*-[0-9]+")) {
            throw new IllegalArgumentException("INVALID_START_ARGUMENTS");
        }
        var result = client.start(PROCESS_ID, projectId, ticketKey, asynchronous);
        if (!asynchronous && !result.path("variables").path("impactAnalysis").isObject()) {
            throw new IllegalStateException("PROCESS_FINISHED_WITHOUT_IMPACT_ANALYSIS");
        }
        return result;
    }

    private static JsonNode inspect(Map<String, String> options, LocalCamundaClient client)
            throws Exception {
        var instance = options.remove("instance");
        if (!options.isEmpty() || instance == null || !instance.matches("[0-9]+")) {
            throw new IllegalArgumentException("INVALID_INSPECT_ARGUMENTS");
        }
        return client.inspect(instance);
    }

    private static JsonNode requireNoOptions(Map<String, String> options, JsonNode result) {
        if (!options.isEmpty()) throw new IllegalArgumentException("INVALID_DEPLOY_ARGUMENTS");
        return result;
    }

    private static Map<String, String> parseOptions(String[] values) {
        var options = new HashMap<String, String>();
        for (int index = 0; index < values.length; index++) {
            var option = values[index];
            if ("--async".equals(option)) {
                if (options.put("async", "true") != null)
                    throw new IllegalArgumentException("DUPLICATE_ARGUMENT");
            } else if ("--project".equals(option)
                    || "--ticket".equals(option)
                    || "--instance".equals(option)) {
                if (++index >= values.length
                        || values[index].startsWith("--")
                        || options.put(option.substring(2), values[index]) != null)
                    throw new IllegalArgumentException("INVALID_ARGUMENT");
            } else throw new IllegalArgumentException("INVALID_ARGUMENT");
        }
        return options;
    }
}
