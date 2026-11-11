package dev.orchestrator.techlead.infrastructure.camunda;

import dev.orchestrator.techlead.application.ticket.ReadTicket;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
public class JiraTicketJobWorker {
    private static final Logger LOG = LoggerFactory.getLogger(JiraTicketJobWorker.class);
    private final ReadTicket readTicket;

    public JiraTicketJobWorker(ReadTicket readTicket) {
        this.readTicket = readTicket;
    }

    @JobWorker(type = "ticket.read", autoComplete = false, fetchVariables = {"projectId", "ticketKey"})
    public void read(JobClient client, ActivatedJob job) {
        var runId = Long.toString(job.getKey());
        try {
            var variables = job.getVariablesAsMap();
            var projectId = string(variables, "projectId");
            var ticketKey = string(variables, "ticketKey");
            var ticket = readTicket.read(projectId, ticketKey);
            client.newCompleteCommand(job.getKey()).variables(Map.of("ticket", ticket)).send().join();
            LOG.atInfo().addKeyValue("runId", runId).addKeyValue("ticketKey", ticketKey).log("ticket_read_completed");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            var retries = e instanceof IllegalArgumentException ? 0 : Math.max(0, job.getRetries() - 1);
            LOG.atWarn().addKeyValue("runId", runId).addKeyValue("errorType", e.getClass().getSimpleName())
                    .addKeyValue("retriesRemaining", retries).log("ticket_read_failed");
            client.newFailCommand(job.getKey()).retries(retries).retryBackoff(Duration.ofSeconds(30))
                    .errorMessage("TICKET_READ_FAILED runId=" + runId + "; type=" + e.getClass().getSimpleName()).send().join();
        }
    }

    private static String string(Map<String, Object> variables, String name) {
        var value = variables.get(name);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("MISSING_" + name.toUpperCase());
        return text;
    }
}
