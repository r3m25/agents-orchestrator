package dev.orchestrator.techlead.application.planning;

import java.util.Map;

public interface ContextSource {
    PlanningContext load(Map<String, Object> variables) throws Exception;
}
