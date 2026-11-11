package dev.orchestrator.techlead.application.planning;

import java.util.List;
import java.util.Map;

public record PlanningContext(String projectId, String ticketKey, Map<String, Object> ticket,
                              List<RepositorySnapshot> repositories, List<String> blockers) {
    public record RepositorySnapshot(String component, String repository, String revision,
                                     List<String> inventory, Map<String, String> files,
                                     boolean truncated) { }
}
