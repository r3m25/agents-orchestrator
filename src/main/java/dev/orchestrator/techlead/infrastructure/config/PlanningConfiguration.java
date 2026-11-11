package dev.orchestrator.techlead.infrastructure.config;

import dev.orchestrator.techlead.application.planning.AnalyzeImpact;
import dev.orchestrator.techlead.application.planning.ImpactPlanningService;
import dev.orchestrator.techlead.application.repository.RepositoryWorkspace;
import dev.orchestrator.techlead.application.ticket.ReadTicket;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orchestrator.techlead.infrastructure.codex.CodexPlanningEngine;
import dev.orchestrator.techlead.infrastructure.git.ExistingCloneWorkspace;
import dev.orchestrator.techlead.infrastructure.git.GitContextSource;
import dev.orchestrator.techlead.infrastructure.git.ManagedGitWorkspace;
import dev.orchestrator.techlead.infrastructure.jira.JiraTicketReader;
import dev.orchestrator.techlead.infrastructure.process.BoundedProcess;
import dev.orchestrator.techlead.infrastructure.run.FileRunStore;
import dev.orchestrator.techlead.infrastructure.validation.JsonPlanContract;
import io.camunda.client.annotation.Deployment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;

@Configuration
@Deployment(resources = "classpath*:/bpmn/**/*.bpmn", ownJarOnly = true)
public class PlanningConfiguration {
    @Bean
    ObjectMapper planningJson() { return new ObjectMapper(); }

    @Bean
    AnalyzeImpact analyzeImpact(ObjectMapper json, Environment environment) throws Exception {
        var process = new BoundedProcess();
        var runtime = Path.of(environment.getProperty("TECH_LEAD_RUNTIME", ".runtime"));
        var mode = environment.getProperty("REPOSITORY_MODE", "remote");
        RepositoryWorkspace workspace = switch (mode) {
            case "remote" -> new ManagedGitWorkspace(Path.of(environment.getProperty("REPOSITORY_WORKSPACES_PATH",
                    runtime.resolve("workspaces").toString())), process, ManagedGitWorkspace.networkEnvironment(), false);
            case "local-snapshot" -> new ExistingCloneWorkspace(Path.of(environment.getProperty("REPOSITORY_PATHS_FILE",
                    "repository-paths.json")), json, process);
            default -> throw new IllegalArgumentException("INVALID_REPOSITORY_MODE");
        };
        return new ImpactPlanningService(
                new GitContextSource(Path.of(environment.getProperty("CAMUNDA_PROJECTS_PATH", "projects")), json, process, workspace),
                new CodexPlanningEngine(json, process, environment.getProperty("CODEX_EXECUTABLE", "codex"), runtime),
                new JsonPlanContract(json), new FileRunStore(runtime.resolve("runs"), json));
    }

    @Bean
    ReadTicket readTicket(ObjectMapper json, Environment environment) {
        return new JiraTicketReader(json,
                Path.of(environment.getProperty("CAMUNDA_PROJECTS_PATH", "projects")),
                HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build(),
                URI.create(required(environment, "JIRA_BASE_URL")),
                required(environment, "JIRA_EMAIL"), required(environment, "JIRA_API_TOKEN"));
    }

    private static String required(Environment environment, String name) {
        var value = environment.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("MISSING_" + name);
        return value;
    }
}
