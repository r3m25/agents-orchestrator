package dev.orchestrator.techlead;

import dev.orchestrator.techlead.application.ticket.ReadTicket;
import dev.orchestrator.techlead.infrastructure.camunda.JiraTicketJobWorker;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.command.CompleteJobCommandStep1;
import io.camunda.client.api.command.FailJobCommandStep1;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.response.CompleteJobResponse;
import io.camunda.client.api.response.FailJobResponse;
import io.camunda.client.api.worker.JobClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JiraTicketJobWorkerTest {
    @Mock ReadTicket readTicket;
    @Mock JobClient client;
    @Mock ActivatedJob job;
    @Mock CompleteJobCommandStep1 complete;
    @Mock FailJobCommandStep1 fail;
    @Mock FailJobCommandStep1.FailJobCommandStep2 failStep;
    @Mock CamundaFuture<CompleteJobResponse> completeFuture;
    @Mock CamundaFuture<FailJobResponse> failFuture;
    @InjectMocks JiraTicketJobWorker worker;

    @Test
    void completesTicketReadWithNormalizedTicket() throws Exception {
        var ticket = Map.<String, Object>of("key", "KIVNES-35", "description", "Criterio");
        when(job.getKey()).thenReturn(123L);
        when(job.getVariablesAsMap()).thenReturn(Map.of("projectId", "kivnes", "ticketKey", "KIVNES-35"));
        when(readTicket.read("kivnes", "KIVNES-35")).thenReturn(ticket);
        when(client.newCompleteCommand(123L)).thenReturn(complete);
        when(complete.variables(Map.of("ticket", ticket))).thenReturn(complete);
        when(complete.send()).thenReturn(completeFuture);

        worker.read(client, job);

        verify(completeFuture).join();
        verify(client, never()).newFailCommand(anyLong());
    }

    @Test
    void createsIncidentForInvalidTicketInput() throws Exception {
        when(job.getKey()).thenReturn(123L);
        when(job.getVariablesAsMap()).thenReturn(Map.of("projectId", "kivnes", "ticketKey", "KIVNES-35"));
        when(readTicket.read("kivnes", "KIVNES-35")).thenThrow(new IllegalArgumentException("invalid"));
        when(client.newFailCommand(123L)).thenReturn(fail);
        when(fail.retries(0)).thenReturn(failStep);
        when(failStep.retryBackoff(Duration.ofSeconds(30))).thenReturn(failStep);
        when(failStep.errorMessage("TICKET_READ_FAILED runId=123; type=IllegalArgumentException")).thenReturn(failStep);
        when(failStep.send()).thenReturn(failFuture);

        worker.read(client, job);

        verify(failFuture).join();
        verify(client, never()).newCompleteCommand(anyLong());
    }
}
