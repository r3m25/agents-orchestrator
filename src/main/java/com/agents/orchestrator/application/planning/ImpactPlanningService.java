package com.agents.orchestrator.application.planning;

import com.agents.orchestrator.application.run.RunStore;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ImpactPlanningService implements AnalyzeImpact {
    private final ContextSource source;
    private final PlanningEngine engine;
    private final PlanContract contract;
    private final RunStore store;

    public ImpactPlanningService(
            ContextSource source, PlanningEngine engine, PlanContract contract, RunStore store) {
        this.source = source;
        this.engine = engine;
        this.contract = contract;
        this.store = store;
    }

    @Override
    public Map<String, Object> analyze(String runId, Map<String, Object> variables)
            throws Exception {
        // The worker uses the immutable Zeebe job key. Redelivery reuses the persisted result.
        var existing = store.load(runId);
        if (existing != null) return existing;
        var context = source.load(variables);
        var result = new LinkedHashMap<>(contract.validate(engine.plan(context), context));
        result.put("runId", runId);
        result.put("readOnly", true);
        result.put("engine", engine.name());
        result.put(
                "repositories",
                context.repositories().stream()
                        .map(r ->
                                Map.of(
                                        "component",
                                        r.component(),
                                        "repository",
                                        r.repository(),
                                        "revision",
                                        r.revision(),
                                        "inspectedFiles",
                                        r.files().keySet(),
                                        "inventoryCount",
                                        r.inventory().size(),
                                        "truncated",
                                        r.truncated()))
                        .toList());
        store.save(runId, result);
        return result;
    }
}
