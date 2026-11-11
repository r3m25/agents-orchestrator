package com.agents.orchestrator.infrastructure.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;

public interface LocalCamundaClient {
    JsonNode deploy(Path bpmn) throws Exception;

    JsonNode start(
            String processDefinitionId, String projectId, String ticketKey, boolean asynchronous)
            throws Exception;

    JsonNode inspect(String instanceKey) throws Exception;
}
