package com.agents.orchestrator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agents.orchestrator.application.planning.AnalyzeImpact;
import com.agents.orchestrator.infrastructure.camunda.TechLeadJobWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.command.*;
import io.camunda.client.api.response.*;
import io.camunda.client.api.worker.JobClient;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.logback.StructuredLogEncoder;

@ExtendWith(MockitoExtension.class)
class TechLeadJobWorkerTest {
    @Mock AnalyzeImpact analyze;
    @Mock JobClient client;
    @Mock ActivatedJob job;
    @Mock CompleteJobCommandStep1 complete;
    @Mock FailJobCommandStep1 fail;
    @Mock FailJobCommandStep1.FailJobCommandStep2 failStep;
    @Mock CamundaFuture<CompleteJobResponse> completeFuture;
    @Mock CamundaFuture<FailJobResponse> failFuture;
    @InjectMocks TechLeadJobWorker worker;

    @Test
    void completesWithStructuredAnalysis() throws Exception {
        when(job.getKey()).thenReturn(123L);
        var result = Map.<String, Object>of("status", "BLOCKED");
        when(analyze.analyze("123", job.getVariablesAsMap())).thenReturn(result);
        when(client.newCompleteCommand(123L)).thenReturn(complete);
        when(complete.variables(Map.of("impactAnalysis", result))).thenReturn(complete);
        when(complete.send()).thenReturn(completeFuture);

        var logger = (Logger) LoggerFactory.getLogger(TechLeadJobWorker.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        MDC.put("processInstanceKey", "456");
        try {
            worker.plan(client, job);
            var encoder = new StructuredLogEncoder();
            logger.getLoggerContext()
                    .putObject(
                            org.springframework.core.env.Environment.class.getName(),
                            new org.springframework.core.env.StandardEnvironment());
            encoder.setContext(logger.getLoggerContext());
            encoder.setFormat("ecs");
            encoder.start();
            for (var event : appender.list) {
                var encoded = new ObjectMapper().readTree(encoder.encode(event));
                assertEquals("456", encoded.path("processInstanceKey").asText());
                assertEquals("123", encoded.path("runId").asText());
            }
            assertEquals(2, appender.list.size());
            encoder.stop();
        } finally {
            MDC.clear();
            logger.detachAppender(appender);
        }

        verify(completeFuture).join();
        verify(client, never()).newFailCommand(anyLong());
    }

    @Test
    void createsIncidentForInvalidInputAndDoesNotExposeSensitiveExceptionText() throws Exception {
        when(job.getKey()).thenReturn(123L);
        when(analyze.analyze("123", job.getVariablesAsMap()))
                .thenThrow(new IllegalArgumentException("token=SECRET"));
        when(client.newFailCommand(123L)).thenReturn(fail);
        when(fail.retries(0)).thenReturn(failStep);
        when(failStep.retryBackoff(Duration.ofSeconds(30))).thenReturn(failStep);
        when(failStep.errorMessage("TECH_LEAD_FAILED runId=123; type=IllegalArgumentException"))
                .thenReturn(failStep);
        when(failStep.send()).thenReturn(failFuture);

        worker.plan(client, job);

        verify(failFuture).join();
        verify(client, never()).newCompleteCommand(anyLong());
    }

    @Test
    void decrementsRetriesForTransientFailure() throws Exception {
        when(job.getKey()).thenReturn(123L);
        when(job.getRetries()).thenReturn(2);
        when(analyze.analyze("123", job.getVariablesAsMap()))
                .thenThrow(new java.io.IOException("unavailable"));
        when(client.newFailCommand(123L)).thenReturn(fail);
        when(fail.retries(1)).thenReturn(failStep);
        when(failStep.retryBackoff(Duration.ofSeconds(30))).thenReturn(failStep);
        when(failStep.errorMessage("TECH_LEAD_FAILED runId=123; type=IOException"))
                .thenReturn(failStep);
        when(failStep.send()).thenReturn(failFuture);

        worker.plan(client, job);

        verify(fail).retries(1);
    }
}
