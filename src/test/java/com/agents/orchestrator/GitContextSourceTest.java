package com.agents.orchestrator;

import static org.junit.jupiter.api.Assertions.*;

import com.agents.orchestrator.infrastructure.git.GitContextSource;
import com.agents.orchestrator.infrastructure.process.BoundedProcess;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GitContextSourceTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();

    private GitContextSource source(String remote) throws Exception {
        return source(remote, "sample-project", "SAMPLE");
    }

    private GitContextSource source(String remote, String projectId, String prefix)
            throws Exception {
        var repo = Files.createDirectories(directory.resolve("repo"));
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.email", "test@example.invalid");
        git(repo, "config", "user.name", "Test");
        git(repo, "remote", "add", "origin", remote);
        Files.writeString(repo.resolve("Service.java"), "class OriginalService {}");
        Files.writeString(repo.resolve(".env"), "SECRET=never-send");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "fixture");
        Files.writeString(repo.resolve("Service.java"), "uncommitted changes must not be read");
        Files.createDirectories(directory.resolve("projects"));
        Files.writeString(
                directory.resolve("projects/" + projectId + ".json"),
                json.writeValueAsString(
                        Map.of(
                                "projectId",
                                projectId,
                                "ticketKeyPattern",
                                "^" + prefix + "-[0-9]+$",
                                "sourceControl",
                                Map.of("provider", "bitbucket", "workspace", "test-workspace"),
                                "repositories",
                                Map.of(
                                        "backend",
                                        Map.of("repository", "test-api", "baseBranch", "main")))));
        Files.writeString(
                directory.resolve("paths.json"),
                json.writeValueAsString(Map.of(projectId, Map.of("test-api", repo.toString()))));
        return new GitContextSource(
                directory.resolve("projects"),
                directory.resolve("paths.json"),
                json,
                new BoundedProcess());
    }

    private Map<String, Object> variables() {
        return Map.of(
                "projectId",
                "sample-project",
                "ticketKey",
                "SAMPLE-1",
                "ticket",
                Map.of("key", "SAMPLE-1", "summary", "Service", "description", "Acceptance"));
    }

    private void git(Path path, String... args) throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of("git", "-C", path.toString()));
        command.addAll(java.util.List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor());
    }

    @ParameterizedTest
    @CsvSource({"sample-project,SAMPLE", "parking-app,PARK"})
    void readsConfiguredProjectCommitAndExcludesSecretsAndUncommittedEdits(
            String projectId, String prefix) throws Exception {
        var input =
                Map.<String, Object>of(
                        "projectId",
                        projectId,
                        "ticketKey",
                        prefix + "-1",
                        "ticket",
                        Map.of(
                                "key",
                                prefix + "-1",
                                "summary",
                                "Service",
                                "description",
                                "Acceptance"));
        var context =
                source("git@bitbucket.org:test-workspace/test-api.git", projectId, prefix)
                        .load(input);
        assertEquals(projectId, context.projectId());
        assertEquals(prefix + "-1", context.ticketKey());
        assertTrue(context.blockers().isEmpty());
        var snapshot = context.repositories().getFirst();
        assertEquals("class OriginalService {}", snapshot.files().get("Service.java"));
        assertFalse(snapshot.files().containsKey(".env"));
        assertEquals(40, snapshot.revision().length());
    }

    @Test
    void recordsBlockerForWrongBitbucketIdentity() throws Exception {
        var context = source("git@bitbucket.org:test-workspace/wrong-api.git").load(variables());
        assertFalse(context.blockers().isEmpty());
        assertTrue(context.repositories().getFirst().files().isEmpty());
    }

    @Test
    void rejectsTicketFromAnotherProject() throws Exception {
        var source = source("git@bitbucket.org:test-workspace/test-api.git");
        var input = new java.util.HashMap<>(variables());
        input.put("ticketKey", "OTHER-1");
        assertThrows(IllegalArgumentException.class, () -> source.load(input));
    }
}
