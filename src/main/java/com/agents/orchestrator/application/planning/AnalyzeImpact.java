package com.agents.orchestrator.application.planning;

import java.util.Map;

public interface AnalyzeImpact {
    Map<String, Object> analyze(String runId, Map<String, Object> variables) throws Exception;
}
