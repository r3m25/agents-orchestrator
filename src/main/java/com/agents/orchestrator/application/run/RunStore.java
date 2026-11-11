package com.agents.orchestrator.application.run;

import java.util.Map;

public interface RunStore {
    Map<String, Object> load(String runId) throws Exception;

    void save(String runId, Map<String, Object> result) throws Exception;
}
