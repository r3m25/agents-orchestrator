package com.agents.orchestrator.infrastructure.camunda;

import com.agents.orchestrator.application.planning.AnalyzeImpact;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TechLeadJobWorker {
    private static final Logger LOG = LoggerFactory.getLogger(TechLeadJobWorker.class);
    private final AnalyzeImpact analyzeImpact;

    public TechLeadJobWorker(AnalyzeImpact analyzeImpact) {
        this.analyzeImpact = analyzeImpact;
    }

    @JobWorker(
            type = "tech-lead.plan-impact.v1",
            autoComplete = false,
            fetchVariables = {"projectId", "ticketKey", "ticket"})
    public void plan(JobClient client, ActivatedJob job) {
        var runId = Long.toString(job.getKey());
        try {
            LOG.atInfo()
                    .addKeyValue("runId", runId)
                    .addKeyValue("workflowInstanceKey", job.getProcessInstanceKey())
                    .log("tech_lead_started");
            var result = analyzeImpact.analyze(runId, job.getVariablesAsMap());
            client.newCompleteCommand(job.getKey())
                    .variables(Map.of("impactAnalysis", result))
                    .send()
                    .join();
            LOG.atInfo()
                    .addKeyValue("runId", runId)
                    .addKeyValue("status", result.get("status"))
                    .log("tech_lead_completed");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // Never serialize upstream exception bodies, prompts, stderr, or credentials into
            // Camunda/logs.
            var permanent = e instanceof IllegalArgumentException;
            var retries = permanent ? 0 : Math.max(0, job.getRetries() - 1);
            LOG.atWarn()
                    .addKeyValue("runId", runId)
                    .addKeyValue("errorType", e.getClass().getSimpleName())
                    .addKeyValue("retriesRemaining", retries)
                    .log("tech_lead_failed");
            client.newFailCommand(job.getKey())
                    .retries(retries)
                    .retryBackoff(Duration.ofSeconds(30))
                    .errorMessage(
                            "TECH_LEAD_FAILED runId="
                                    + runId
                                    + "; type="
                                    + e.getClass().getSimpleName())
                    .send()
                    .join();
        }
    }
}
