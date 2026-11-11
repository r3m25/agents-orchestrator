package com.agents.orchestrator.infrastructure.cli;

import com.agents.orchestrator.application.repository.PrepareWorkBranch;
import com.agents.orchestrator.infrastructure.git.GitRepositoryConfig;
import com.agents.orchestrator.infrastructure.git.ManagedGitWorkspace;
import com.agents.orchestrator.infrastructure.process.BoundedProcess;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Explicit development operation; never invoked by the read-only planning worker. */
public final class PrepareWorkBranchCommand {
    private PrepareWorkBranchCommand() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3
                || !args[0].matches("[a-z0-9-]{1,64}")
                || !args[1].matches("[a-zA-Z0-9_-]+")
                || args[2].isBlank()) {
            throw new IllegalArgumentException("EXPECTED_PROJECT_COMPONENT_BRANCH");
        }
        var json = new ObjectMapper();
        var projects =
                Path.of(
                        System.getenv()
                                .getOrDefault("CAMUNDA_PROJECTS_PATH", "../../config/projects"));
        var profile = json.readTree(Files.readString(projects.resolve(args[0] + ".json")));
        if (!args[0].equals(profile.path("projectId").asText())
                || !profile.path("repositories").has(args[1])) {
            throw new IllegalArgumentException("INVALID_PROJECT_COMPONENT");
        }
        var repository = profile.path("repositories").path(args[1]);
        PrepareWorkBranch workspace =
                new ManagedGitWorkspace(
                        Path.of(
                                System.getenv()
                                        .getOrDefault(
                                                "REPOSITORY_WORKSPACES_PATH",
                                                ".runtime/workspaces")),
                        new BoundedProcess(),
                        ManagedGitWorkspace.networkEnvironment(),
                        false);
        var prepared =
                workspace.prepareWorkBranch(
                        args[0],
                        repository.path("repository").asText(),
                        GitRepositoryConfig.resolve(profile, repository),
                        args[2]);
        System.out.println(
                json.writeValueAsString(
                        Map.of(
                                "projectId",
                                args[0],
                                "component",
                                args[1],
                                "branch",
                                args[2],
                                "directory",
                                prepared.directory().toString(),
                                "revision",
                                prepared.revision())));
    }
}
