package dev.orchestrator.techlead.application.planning;

import java.util.Map;

public interface PlanContract {
    Map<String, Object> validate(String json, PlanningContext context) throws Exception;
}
