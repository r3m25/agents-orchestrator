package com.agents.orchestrator.infrastructure.validation;

import com.agents.orchestrator.application.planning.PlanContract;
import com.agents.orchestrator.application.planning.PlanningContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.util.*;

public final class JsonPlanContract implements PlanContract {
    private final ObjectMapper json;
    private final com.networknt.schema.JsonSchema schema;

    public JsonPlanContract(ObjectMapper json) throws Exception {
        this.json = json;
        try (var input = getClass().getResourceAsStream("/tech-lead-output.schema.json")) {
            schema =
                    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
                            .getSchema(json.readTree(input));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> validate(String value, PlanningContext context) throws Exception {
        if (value.length() > 150_000) throw new IllegalArgumentException("PLAN_TOO_LARGE");
        var plan = json.readTree(value);
        if (!schema.validate(plan).isEmpty())
            throw new IllegalArgumentException("INVALID_PLAN_SCHEMA");
        if (!context.ticketKey().equals(plan.path("ticket").asText()))
            throw new IllegalArgumentException("PLAN_TICKET_MISMATCH");
        var seen = new HashSet<String>();
        for (var component : plan.path("affectedComponents")) {
            var snapshot =
                    context.repositories().stream()
                            .filter(r -> r.component().equals(component.path("component").asText()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("UNKNOWN_COMPONENT"));
            if (!seen.add(snapshot.component()))
                throw new IllegalArgumentException("DUPLICATE_COMPONENT");
            for (var repo : component.path("repositories")) {
                if (!snapshot.repository().equals(repo.asText()))
                    throw new IllegalArgumentException("UNKNOWN_REPOSITORY");
            }
            if (snapshot.files().isEmpty()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) component).putNull("affected");
            }
            if (!component.path("affected").isNull() && component.path("evidence").isEmpty()) {
                throw new IllegalArgumentException("MISSING_COMPONENT_EVIDENCE");
            }
            for (var evidence : component.path("evidence")) {
                if (!snapshot.repository().equals(evidence.path("repository").asText()))
                    throw new IllegalArgumentException("WRONG_COMPONENT_EVIDENCE");
                validateEvidence(evidence, context);
            }
        }
        if (seen.size() != context.repositories().size())
            throw new IllegalArgumentException("MISSING_COMPONENTS");
        for (var fact : plan.path("facts")) {
            if (fact.path("evidence").isEmpty())
                throw new IllegalArgumentException("UNSUPPORTED_FACT");
            for (var evidence : fact.path("evidence")) validateEvidence(evidence, context);
        }
        var tasks = new LinkedHashMap<String, JsonNode>();
        for (var task : plan.path("tasks")) {
            if (task.path("id").asText().isBlank()
                    || tasks.put(task.path("id").asText(), task) != null) {
                throw new IllegalArgumentException("DUPLICATE_OR_EMPTY_TASK");
            }
        }
        var visited = new HashSet<String>();
        for (var id : tasks.keySet()) visit(id, tasks, visited, new HashSet<>());
        var blockers = (List<String>) json.convertValue(plan.path("blockers"), List.class);
        context.blockers()
                .forEach(b -> {
                    if (!blockers.contains(b)) blockers.add(b);
                });
        var result = (Map<String, Object>) json.convertValue(plan, Map.class);
        if (!blockers.isEmpty()) {
            result.put("status", "BLOCKED");
            result.put("recommendedNextAction", "REQUEST_INFORMATION");
        } else if (!"PLANNED".equals(result.get("status"))
                || !"REQUEST_APPROVAL".equals(result.get("recommendedNextAction"))
                || plan.path("tasks").isEmpty()
                || plan.path("acceptanceCriteria").isEmpty()
                || plan.path("requiredApprovals").isEmpty()) {
            throw new IllegalArgumentException("INCONSISTENT_PLAN_STATUS");
        }
        result.put("blockers", blockers);
        return result;
    }

    private void validateEvidence(JsonNode evidence, PlanningContext context) {
        var snapshot =
                context.repositories().stream()
                        .filter(r -> r.repository().equals(evidence.path("repository").asText()))
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalArgumentException("UNKNOWN_EVIDENCE_REPOSITORY"));
        var content = snapshot.files().get(evidence.path("path").asText());
        var quote = evidence.path("quote").asText();
        if (!snapshot.revision().equals(evidence.path("revision").asText())
                || content == null
                || quote.isBlank()
                || !content.contains(quote))
            throw new IllegalArgumentException("UNVERIFIABLE_EVIDENCE");
    }

    private void visit(
            String id, Map<String, JsonNode> tasks, Set<String> visited, Set<String> visiting) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("CYCLIC_PLAN");
        var task = tasks.get(id);
        if (task == null) throw new IllegalArgumentException("UNKNOWN_TASK_DEPENDENCY");
        for (var dependency : task.path("dependencies"))
            visit(dependency.asText(), tasks, visited, visiting);
        visiting.remove(id);
        visited.add(id);
    }
}
