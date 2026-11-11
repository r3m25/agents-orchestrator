package com.agents.orchestrator.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.agents.orchestrator.application.planning.AnalyzeImpact;
import com.agents.orchestrator.application.ticket.ReadTicket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(PlanningConfiguration.class)
@TestPropertySource(
        properties = {
            "JIRA_BASE_URL=http://127.0.0.1:8088",
            "JIRA_EMAIL=integration-test@example.test",
            "JIRA_API_TOKEN=integration-test-token",
            "CAMUNDA_PROJECTS_PATH=projects",
            "TECH_LEAD_RUNTIME=build/integration-test-runtime"
        })
class PlanningConfigurationIntegrationTest {
    @Autowired private AnalyzeImpact analyzeImpact;

    @Autowired private ReadTicket readTicket;

    @Autowired private ObjectMapper planningJson;

    @Test
    void wiresPlanningUseCaseAndTicketReaderThroughSpringConfiguration() {
        assertNotNull(analyzeImpact);
        assertNotNull(readTicket);
        assertNotNull(planningJson);
    }
}
