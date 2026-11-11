package com.agents.orchestrator;

import static org.junit.jupiter.api.Assertions.*;

import com.agents.orchestrator.application.repository.RepositorySource;
import com.agents.orchestrator.infrastructure.git.GitContextSource;
import com.agents.orchestrator.infrastructure.git.ManagedGitWorkspace;
import com.agents.orchestrator.infrastructure.process.BoundedProcess;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real Git transports to temporary bare repositories; no hosted-provider credentials required. */
class ManagedGitWorkspaceTest {
    @TempDir Path directory;
    Path remote;
    Path seed;
    ManagedGitWorkspace workspace;
    RepositorySource source;
    final BoundedProcess process = new BoundedProcess();

    @BeforeEach
    void createRemote() throws Exception {
        remote = directory.resolve("remote.git");
        seed = Files.createDirectory(directory.resolve("seed"));
        git(directory, "init", "--bare", "--initial-branch=main", remote.toString());
        git(seed, "init", "-b", "main");
        git(seed, "config", "user.name", "Fixture");
        git(seed, "config", "user.email", "fixture@example.invalid");
        git(seed, "remote", "add", "origin", remote.toUri().toString());
        Files.writeString(seed.resolve("Service.java"), "class Original {}");
        git(seed, "add", ".");
        git(seed, "commit", "-m", "Initial");
        git(seed, "push", "origin", "main");
        source = new RepositorySource("git", remote.toUri().toString(), "main");
        workspace =
                new ManagedGitWorkspace(
                        directory.resolve("managed"),
                        process,
                        BoundedProcess.gitEnvironment(),
                        true);
    }

    @Test
    void clonesRemoteAndChecksOutConfiguredBaseBranch() throws Exception {
        var prepared = workspace.prepare("sample-project", "api", source);
        assertEquals("main", git(prepared.directory(), "branch", "--show-current").trim());
        assertEquals(git(seed, "rev-parse", "HEAD").trim(), prepared.revision());
        assertEquals(
                "class Original {}",
                Files.readString(prepared.directory().resolve("Service.java")));
    }

    @Test
    void pullsNewRemoteCommitIntoExistingWorkspace() throws Exception {
        var initial = workspace.prepare("sample-project", "api", source);
        Files.writeString(seed.resolve("Service.java"), "class Updated {}");
        git(seed, "commit", "-am", "Updated");
        git(seed, "push", "origin", "main");

        var updated = workspace.prepare("sample-project", "api", source);

        assertEquals(initial.directory(), updated.directory());
        assertNotEquals(initial.revision(), updated.revision());
        assertEquals(git(seed, "rev-parse", "HEAD").trim(), updated.revision());
        assertEquals(
                "class Updated {}", Files.readString(updated.directory().resolve("Service.java")));
    }

    @Test
    void createsWorkBranchOnRemoteBeforeCheckingItOutLocally() throws Exception {
        var prepared =
                workspace.prepareWorkBranch(
                        "sample-project", "api", source, "feature/SAMPLE-1/test");
        assertEquals(
                "feature/SAMPLE-1/test",
                git(prepared.directory(), "branch", "--show-current").trim());
        assertEquals(
                prepared.revision(),
                git(remote, "rev-parse", "refs/heads/feature/SAMPLE-1/test").trim());
        assertEquals(git(seed, "rev-parse", "main").trim(), prepared.revision());
    }

    @Test
    void reusesExistingRemoteWorkBranchWithoutOverwritingIt() throws Exception {
        git(seed, "checkout", "-b", "feature/SAMPLE-1/test");
        Files.writeString(seed.resolve("Service.java"), "class Feature {}");
        git(seed, "commit", "-am", "Existing work");
        git(seed, "push", "origin", "feature/SAMPLE-1/test");
        var expected = git(seed, "rev-parse", "HEAD").trim();

        var prepared =
                workspace.prepareWorkBranch(
                        "sample-project", "api", source, "feature/SAMPLE-1/test");
        var repeated =
                workspace.prepareWorkBranch(
                        "sample-project", "api", source, "feature/SAMPLE-1/test");

        assertEquals(expected, prepared.revision());
        assertEquals(expected, repeated.revision());
        assertEquals(prepared.directory(), repeated.directory());
        assertEquals(expected, git(remote, "rev-parse", "refs/heads/feature/SAMPLE-1/test").trim());
    }

    @Test
    void refusesToDiscardUncommittedWork() throws Exception {
        var prepared = workspace.prepare("sample-project", "api", source);
        Files.writeString(prepared.directory().resolve("Service.java"), "local edits");
        assertThrows(
                IllegalStateException.class,
                () -> workspace.prepare("sample-project", "api", source));
        assertEquals("local edits", Files.readString(prepared.directory().resolve("Service.java")));
    }

    @Test
    void doesNotCreateRemoteBranchesDuringPlanning() throws Exception {
        var before = git(remote, "for-each-ref", "--format=%(refname)", "refs/heads/");
        workspace.prepare("sample-project", "api", source);
        assertEquals(before, git(remote, "for-each-ref", "--format=%(refname)", "refs/heads/"));
    }

    @Test
    void rejectsBaseBranchAsWorkBranch() {
        assertThrows(
                IllegalArgumentException.class,
                () -> workspace.prepareWorkBranch("sample-project", "api", source, "main"));
    }

    @Test
    void doesNotFallBackToStaleLocalCopyWhenRemoteIsUnavailable() throws Exception {
        workspace.prepare("sample-project", "api", source);
        Files.move(remote, directory.resolve("offline.git"));
        assertThrows(Exception.class, () -> workspace.prepare("sample-project", "api", source));
    }

    @Test
    void isolatesProjectWorkspaces() throws Exception {
        var first = workspace.prepare("sample-project", "api", source);
        var second = workspace.prepare("parking-app", "api", source);
        assertNotEquals(first.directory(), second.directory());
        assertEquals(first.revision(), second.revision());
    }

    @Test
    void refusesLocalFilesInProductionTransport() {
        var production =
                new ManagedGitWorkspace(
                        directory.resolve("production"),
                        process,
                        BoundedProcess.gitEnvironment(),
                        false);
        assertThrows(
                IllegalArgumentException.class,
                () -> production.prepare("sample-project", "api", source));
    }

    @Test
    void planningInspectsTheCommitFetchedFromConfiguredRemote() throws Exception {
        var projects = Files.createDirectory(directory.resolve("projects"));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Files.writeString(
                projects.resolve("sample-project.json"),
                json.writeValueAsString(
                        Map.of(
                                "projectId",
                                "sample-project",
                                "ticketKeyPattern",
                                "^SAMPLE-[0-9]+$",
                                "sourceControl",
                                Map.of("provider", "git"),
                                "repositories",
                                Map.of(
                                        "api",
                                        Map.of(
                                                "repository",
                                                "api",
                                                "baseBranch",
                                                "main",
                                                "cloneUrl",
                                                source.cloneUrl())))));
        var contextSource = new GitContextSource(projects, json, process, workspace);

        var context =
                contextSource.load(
                        Map.of(
                                "projectId",
                                "sample-project",
                                "ticketKey",
                                "SAMPLE-1",
                                "ticket",
                                Map.of(
                                        "key",
                                        "SAMPLE-1",
                                        "summary",
                                        "Service",
                                        "description",
                                        "Acceptance")));

        assertTrue(context.blockers().isEmpty());
        assertEquals(
                "class Original {}", context.repositories().getFirst().files().get("Service.java"));
        assertEquals(
                git(seed, "rev-parse", "HEAD").trim(),
                context.repositories().getFirst().revision());
    }

    @Test
    void preservesLocalCommitsAndRefusesToReportThemAsRemoteState() throws Exception {
        var prepared = workspace.prepare("sample-project", "api", source);
        git(prepared.directory(), "config", "user.name", "Fixture");
        git(prepared.directory(), "config", "user.email", "fixture@example.invalid");
        Files.writeString(prepared.directory().resolve("Service.java"), "class LocalCommit {}");
        git(prepared.directory(), "commit", "-am", "Local work");
        var local = git(prepared.directory(), "rev-parse", "HEAD").trim();

        assertThrows(
                IllegalStateException.class,
                () -> workspace.prepare("sample-project", "api", source));

        assertEquals(local, git(prepared.directory(), "rev-parse", "HEAD").trim());
        assertNotEquals(local, git(remote, "rev-parse", "refs/heads/main").trim());
    }

    @Test
    void rejectsChangedOriginBeforeFetching() throws Exception {
        var prepared = workspace.prepare("sample-project", "api", source);
        git(
                prepared.directory(),
                "remote",
                "set-url",
                "origin",
                directory.resolve("other.git").toUri().toString());
        assertThrows(
                IllegalArgumentException.class,
                () -> workspace.prepare("sample-project", "api", source));
    }

    @Test
    void removesIncompleteCloneWhenConfiguredBaseDoesNotExist() throws Exception {
        var invalid = new RepositorySource("git", source.cloneUrl(), "missing");
        assertThrows(Exception.class, () -> workspace.prepare("sample-project", "api", invalid));
        try (var paths = Files.list(directory.resolve("managed/sample-project/api"))) {
            assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith("clone-")));
        }
        assertNotNull(workspace.prepare("sample-project", "api", source));
    }

    @Test
    void planningAcceptsConfiguredReleaseBranchWithDots() throws Exception {
        git(seed, "checkout", "-b", "release/1.0");
        git(seed, "push", "origin", "release/1.0");
        var projects = Files.createDirectory(directory.resolve("projects"));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Files.writeString(
                projects.resolve("sample-project.json"),
                json.writeValueAsString(
                        Map.of(
                                "projectId",
                                "sample-project",
                                "ticketKeyPattern",
                                "^SAMPLE-[0-9]+$",
                                "sourceControl",
                                Map.of("provider", "git"),
                                "repositories",
                                Map.of(
                                        "api",
                                        Map.of(
                                                "repository",
                                                "example.api",
                                                "baseBranch",
                                                "release/1.0",
                                                "cloneUrl",
                                                source.cloneUrl())))));

        var context =
                new GitContextSource(projects, json, process, workspace)
                        .load(
                                Map.of(
                                        "projectId",
                                        "sample-project",
                                        "ticketKey",
                                        "SAMPLE-1",
                                        "ticket",
                                        Map.of(
                                                "key",
                                                "SAMPLE-1",
                                                "summary",
                                                "Service",
                                                "description",
                                                "Acceptance")));

        assertTrue(context.blockers().isEmpty());
        assertEquals("example.api", context.repositories().getFirst().repository());
        assertEquals(
                git(seed, "rev-parse", "release/1.0").trim(),
                context.repositories().getFirst().revision());
    }

    private String git(Path root, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "protocol.file.allow=always"));
        command.addAll(List.of(args));
        return process.run(
                command, root, Duration.ofSeconds(10), 100_000, BoundedProcess.gitEnvironment());
    }
}
