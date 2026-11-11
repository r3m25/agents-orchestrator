package dev.orchestrator.techlead.application.planning;

public interface PlanningEngine {
    String name();
    String plan(PlanningContext context) throws Exception;
}
