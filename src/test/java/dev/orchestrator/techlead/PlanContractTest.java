package dev.orchestrator.techlead;

import dev.orchestrator.techlead.application.planning.PlanningContext;
import dev.orchestrator.techlead.application.planning.PlanningContext.RepositorySnapshot;
import dev.orchestrator.techlead.infrastructure.validation.JsonPlanContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanContractTest {
    final ObjectMapper json = new ObjectMapper();
    JsonPlanContract contract;
    PlanningContext context;
    ObjectNode plan;

    @BeforeEach
    void createPlan() throws Exception {
        contract = new JsonPlanContract(json);
        context = new PlanningContext("sample-project", "SAMPLE-1", Map.of("description", "Acceptance: expose summary"),
                List.of(new RepositorySnapshot("backend", "api", "abc", List.of("Service.java"),
                        Map.of("Service.java", "class Service {}"), false)), List.of());
        plan = (ObjectNode) json.readTree("""
                {"ticket":"SAMPLE-1","status":"PLANNED","summary":"Impacto",
                 "affectedComponents":[{"component":"backend","affected":true,"reason":"Add behavior",
                 "repositories":["api"],"evidence":[{"repository":"api","revision":"abc","path":"Service.java","quote":"class Service"}]}],
                 "facts":[],"inferences":[],"decisions":[],
                 "tasks":[{"id":"t1","agent":"backend","description":"Implement","dependencies":[]}],
                 "risks":[],"blockers":[],"requiredApprovals":["Human review"],
                 "acceptanceCriteria":["Expose summary"],"recommendedNextAction":"REQUEST_APPROVAL"}
                """);
    }

    @Test void acceptsEvidenceFromPinnedFile() throws Exception {
        assertEquals("PLANNED", contract.validate(plan.toString(), context).get("status"));
    }
    @Test void rejectsInventedQuote() {
        ((ObjectNode) plan.at("/affectedComponents/0/evidence/0")).put("quote", "invented endpoint");
        assertThrows(IllegalArgumentException.class, () -> contract.validate(plan.toString(), context));
    }
    @Test void rejectsWrongRevision() {
        ((ObjectNode) plan.at("/affectedComponents/0/evidence/0")).put("revision", "other");
        assertThrows(IllegalArgumentException.class, () -> contract.validate(plan.toString(), context));
    }
    @Test void rejectsInventedFile() {
        ((ObjectNode) plan.at("/affectedComponents/0/evidence/0")).put("path", "missing.java");
        assertThrows(IllegalArgumentException.class, () -> contract.validate(plan.toString(), context));
    }
    @Test void rejectsUnknownComponent() {
        ((ObjectNode) plan.at("/affectedComponents/0")).put("component", "ios");
        assertThrows(IllegalArgumentException.class, () -> contract.validate(plan.toString(), context));
    }
    @Test void rejectsUnknownDependency() {
        ((com.fasterxml.jackson.databind.node.ArrayNode) plan.at("/tasks/0/dependencies")).add("missing");
        assertThrows(IllegalArgumentException.class, () -> contract.validate(plan.toString(), context));
    }
    @Test void rejectsDependencyCycle() {
        ((com.fasterxml.jackson.databind.node.ArrayNode) plan.at("/tasks/0/dependencies")).add("t1");
        assertThrows(IllegalArgumentException.class, () -> contract.validate(plan.toString(), context));
    }
    @Test void rejectsSchemaMismatch() {
        plan.put("unexpected", "value");
        assertThrows(IllegalArgumentException.class, () -> contract.validate(plan.toString(), context));
    }
    @Test void rejectsTicketMismatch() {
        plan.put("ticket", "SAMPLE-2");
        assertThrows(IllegalArgumentException.class, () -> contract.validate(plan.toString(), context));
    }
    @Test void makesUnavailableRepositoryAnUnavoidableBlocker() throws Exception {
        var blocked = new PlanningContext(context.projectId(), context.ticketKey(), context.ticket(), context.repositories(), List.of("backend unavailable"));
        var result = contract.validate(plan.toString(), blocked);
        assertEquals("BLOCKED", result.get("status"));
        assertEquals("REQUEST_INFORMATION", result.get("recommendedNextAction"));
        assertEquals(List.of("backend unavailable"), result.get("blockers"));
    }
    @Test void representsUnavailableRepositoryAsUnknownRatherThanUnaffected() throws Exception {
        var unavailable = new PlanningContext(context.projectId(), context.ticketKey(), context.ticket(),
                List.of(new RepositorySnapshot("backend", "api", "", List.of(), Map.of(), true)),
                List.of("backend unavailable"));
        ((com.fasterxml.jackson.databind.node.ArrayNode) plan.at("/affectedComponents/0/evidence")).removeAll();
        ((ObjectNode) plan.at("/affectedComponents/0")).put("affected", false);

        var result = contract.validate(plan.toString(), unavailable);

        var components = (List<?>) result.get("affectedComponents");
        assertNull(((Map<?, ?>) components.getFirst()).get("affected"));
        assertEquals("BLOCKED", result.get("status"));
    }
    @Test void rejectsUnsupportedAssertionOfNoImpact() {
        ((com.fasterxml.jackson.databind.node.ArrayNode) plan.at("/affectedComponents/0/evidence")).removeAll();
        ((ObjectNode) plan.at("/affectedComponents/0")).put("affected", false);
        assertThrows(IllegalArgumentException.class, () -> contract.validate(plan.toString(), context));
    }
}
