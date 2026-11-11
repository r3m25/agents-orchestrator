package com.agents.orchestrator.infrastructure.config;

import com.agents.orchestrator.application.planning.AnalyzeImpact;
import com.agents.orchestrator.application.planning.ImpactPlanningService;
import com.agents.orchestrator.application.repository.RepositoryWorkspace;
import com.agents.orchestrator.application.ticket.ReadTicket;
import com.agents.orchestrator.infrastructure.codex.CodexPlanningEngine;
import com.agents.orchestrator.infrastructure.git.ExistingCloneWorkspace;
import com.agents.orchestrator.infrastructure.git.GitContextSource;
import com.agents.orchestrator.infrastructure.git.ManagedGitWorkspace;
import com.agents.orchestrator.infrastructure.jira.JiraTicketReader;
import com.agents.orchestrator.infrastructure.process.BoundedProcess;
import com.agents.orchestrator.infrastructure.run.FileRunStore;
import com.agents.orchestrator.infrastructure.validation.JsonPlanContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.annotation.Deployment;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@Deployment(resources = "classpath*:/bpmn/**/*.bpmn", ownJarOnly = true)
public class PlanningConfiguration {
    @Bean
    ObjectMapper planningJson() {
        return new ObjectMapper();
    }

    @Bean
    AnalyzeImpact analyzeImpact(ObjectMapper json, Environment environment) throws Exception {
        var process = new BoundedProcess();
        var runtime = Path.of(environment.getProperty("TECH_LEAD_RUNTIME", ".runtime"));
        var mode = environment.getProperty("REPOSITORY_MODE", "remote");
        RepositoryWorkspace workspace =
                switch (mode) {
                    case "remote" ->
                            new ManagedGitWorkspace(
                                    Path.of(
                                            environment.getProperty(
                                                    "REPOSITORY_WORKSPACES_PATH",
                                                    runtime.resolve("workspaces").toString())),
                                    process,
                                    ManagedGitWorkspace.networkEnvironment(),
                                    false);
                    case "local-snapshot" ->
                            new ExistingCloneWorkspace(
                                    Path.of(
                                            environment.getProperty(
                                                    "REPOSITORY_PATHS_FILE",
                                                    "repository-paths.json")),
                                    json,
                                    process);
                    default -> throw new IllegalArgumentException("INVALID_REPOSITORY_MODE");
                };
        return new ImpactPlanningService(
                new GitContextSource(
                        Path.of(environment.getProperty("CAMUNDA_PROJECTS_PATH", "projects")),
                        json,
                        process,
                        workspace),
                new CodexPlanningEngine(
                        json,
                        process,
                        environment.getProperty("CODEX_EXECUTABLE", "codex"),
                        runtime),
                new JsonPlanContract(json),
                new FileRunStore(runtime.resolve("runs"), json));
    }

    @Bean
    ReadTicket readTicket(ObjectMapper json, Environment environment) {
        return new JiraTicketReader(
                json,
                Path.of(environment.getProperty("CAMUNDA_PROJECTS_PATH", "projects")),
                HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build(),
                URI.create(required(environment, "JIRA_BASE_URL")),
                required(environment, "JIRA_EMAIL"),
                required(environment, "JIRA_API_TOKEN"));
    }

    private static String required(Environment environment, String name) {
        var value = environment.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("MISSING_" + name);
        return value;
    }
}
