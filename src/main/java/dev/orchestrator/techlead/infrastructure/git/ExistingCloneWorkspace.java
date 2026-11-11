package dev.orchestrator.techlead.infrastructure.git;

import dev.orchestrator.techlead.application.repository.RepositorySource;
import dev.orchestrator.techlead.application.repository.RepositoryWorkspace;
import dev.orchestrator.techlead.infrastructure.process.BoundedProcess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Explicit offline mode for existing clones; does not synchronize remote refs. */
public final class ExistingCloneWorkspace implements RepositoryWorkspace {
    private final Path mappings;
    private final ObjectMapper json;
    private final BoundedProcess process;
    public ExistingCloneWorkspace(Path mappings, ObjectMapper json, BoundedProcess process) {
        this.mappings = mappings; this.json = json; this.process = process;
    }
    @Override
    public PreparedRepository prepare(String projectId, String repository, RepositorySource source) throws Exception {
        var path = json.readTree(Files.readString(mappings)).path(projectId).path(repository).asText();
        if (path.isBlank()) throw new IllegalArgumentException("REPOSITORY_NOT_MAPPED");
        var root = Path.of(path).toRealPath();
        var remote = git(root, "remote", "get-url", "origin").trim();
        if (!GitRepositoryConfig.identity(remote, false).equals(GitRepositoryConfig.identity(source.cloneUrl(), false))) {
            throw new IllegalArgumentException("REPOSITORY_IDENTITY_MISMATCH");
        }
        return new PreparedRepository(root, git(root, "rev-parse", "--verify", "refs/heads/" + source.baseBranch() + "^{commit}").trim());
    }
    private String git(Path root, String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "--no-pager", "-c", "core.fsmonitor=false"));
        command.addAll(List.of(args));
        return process.run(command, root, Duration.ofSeconds(10), 2_000_000, BoundedProcess.gitEnvironment());
    }
}
