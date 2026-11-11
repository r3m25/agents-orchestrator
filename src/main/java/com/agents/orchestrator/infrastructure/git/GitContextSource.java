package com.agents.orchestrator.infrastructure.git;

import com.agents.orchestrator.application.planning.ContextSource;
import com.agents.orchestrator.application.planning.PlanningContext;
import com.agents.orchestrator.application.planning.PlanningContext.RepositorySnapshot;
import com.agents.orchestrator.application.repository.RepositoryWorkspace;
import com.agents.orchestrator.infrastructure.process.BoundedProcess;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

public final class GitContextSource implements ContextSource {
    private final Path projects;
    private final RepositoryWorkspace workspace;
    private final ObjectMapper json;
    private final BoundedProcess process;

    public GitContextSource(
            Path projects, Path repositories, ObjectMapper json, BoundedProcess process) {
        this(projects, json, process, new ExistingCloneWorkspace(repositories, json, process));
    }

    public GitContextSource(
            Path projects,
            ObjectMapper json,
            BoundedProcess process,
            RepositoryWorkspace workspace) {
        this.projects = projects;
        this.json = json;
        this.process = process;
        this.workspace = workspace;
    }

    @Override
    @SuppressWarnings("unchecked")
    public PlanningContext load(Map<String, Object> variables) throws Exception {
        var projectId = Objects.toString(variables.get("projectId"), "");
        var ticketKey = Objects.toString(variables.get("ticketKey"), "");
        if (!projectId.matches("[a-z0-9-]{1,64}") || !ticketKey.matches("[A-Z][A-Z0-9]*-[0-9]+")) {
            throw new IllegalArgumentException("INVALID_INPUT");
        }
        var profile = json.readTree(Files.readString(projects.resolve(projectId + ".json")));
        if (!profile.path("projectId").asText().equals(projectId)
                || !ticketKey.matches(profile.path("ticketKeyPattern").asText())) {
            throw new IllegalArgumentException("TICKET_PROJECT_MISMATCH");
        }
        if (!(variables.get("ticket") instanceof Map<?, ?> rawTicket)
                || !ticketKey.equals(rawTicket.get("key"))
                || !(rawTicket.get("description") instanceof String)) {
            throw new IllegalArgumentException("INVALID_TICKET_CONTEXT");
        }
        var ticket = (Map<String, Object>) rawTicket;
        if (json.writeValueAsBytes(ticket).length > 100_000)
            throw new IllegalArgumentException("TICKET_TOO_LARGE");
        var snapshots = new ArrayList<RepositorySnapshot>();
        var blockers = new ArrayList<String>();
        var fields = profile.path("repositories").properties().iterator();
        if (profile.path("repositories").isEmpty() || profile.path("repositories").size() > 10) {
            throw new IllegalArgumentException("INVALID_REPOSITORY_COUNT");
        }
        var sourceDeadline = System.nanoTime() + Duration.ofSeconds(180).toNanos();
        while (fields.hasNext()) {
            var entry = fields.next();
            var component = entry.getKey();
            var repo = entry.getValue().path("repository").asText();
            var branch = entry.getValue().path("baseBranch").asText();
            if (!repo.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*")
                    || !branch.matches("[a-zA-Z0-9][a-zA-Z0-9/_.-]*")) {
                throw new IllegalArgumentException("INVALID_REPOSITORY_PROFILE");
            }
            try {
                if (System.nanoTime() > sourceDeadline)
                    throw new IllegalStateException("CONTEXT_SOURCE_TIMEOUT");
                var source = GitRepositoryConfig.resolve(profile, entry.getValue());
                var prepared = workspace.prepare(projectId, repo, source);
                var root = prepared.directory();
                var revision = prepared.revision();
                if (!revision.matches("[a-f0-9]{40,64}"))
                    throw new IllegalArgumentException("INVALID_REVISION");
                var entries =
                        Arrays.asList(git(root, "ls-tree", "-r", "-z", revision).split("\u0000"));
                var inventory = new ArrayList<String>();
                for (var treeEntry : entries) {
                    // Only regular blobs; symlinks and submodules are never traversed.
                    if (treeEntry.startsWith("100644 blob ")
                            || treeEntry.startsWith("100755 blob ")) {
                        inventory.add(treeEntry.substring(treeEntry.indexOf('\t') + 1));
                    }
                }
                var tokens =
                        (ticket.get("summary") + " " + ticket.get("description"))
                                .toLowerCase(Locale.ROOT)
                                .split("[^a-z0-9]+");
                inventory.sort(
                        Comparator.<String>comparingInt(p -> score(p, tokens))
                                .reversed()
                                .thenComparing(p -> p));
                var files = new LinkedHashMap<String, String>();
                var deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
                for (var path : inventory) {
                    if (System.nanoTime() > deadline)
                        throw new IllegalStateException("INSPECTION_TIMEOUT");
                    if (files.size() >= 10) break;
                    if (!safeText(path)) continue;
                    var size =
                            Integer.parseInt(
                                    git(root, "cat-file", "-s", revision + ":" + path).trim());
                    if (size > 12_000) continue;
                    files.put(path, git(root, "show", revision + ":" + path));
                }
                snapshots.add(
                        new RepositorySnapshot(
                                component,
                                repo,
                                revision,
                                inventory.stream().limit(1500).toList(),
                                files,
                                inventory.size() > files.size()));
                if (files.isEmpty()) blockers.add(component + ": NO_INSPECTABLE_FILES");
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                blockers.add(
                        component + ": REPOSITORY_UNAVAILABLE_OR_IDENTITY_MISMATCH (" + repo + ")");
                snapshots.add(
                        new RepositorySnapshot(component, repo, "", List.of(), Map.of(), true));
            }
        }
        return new PlanningContext(projectId, ticketKey, ticket, snapshots, blockers);
    }

    private String git(Path root, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "--no-pager", "-c", "core.fsmonitor=false"));
        command.addAll(List.of(arguments));
        return process.run(
                command, root, Duration.ofSeconds(10), 2_000_000, BoundedProcess.gitEnvironment());
    }

    private static int score(String path, String[] tokens) {
        var lower = path.toLowerCase(Locale.ROOT);
        int score =
                lower.matches(".*(readme|build.gradle|pom.xml|package.json|pubspec.yaml).*")
                        ? 8
                        : 0;
        for (var token : tokens) if (token.length() >= 4 && lower.contains(token)) score += 3;
        return score;
    }

    private static boolean safeText(String path) {
        var lower = path.toLowerCase(Locale.ROOT);
        return !lower.matches(
                        ".*(secret|credential|token|password|lock|vendor/|node_modules/|generated/|application[^/]*\\.(yml|yaml|properties)|(^|/)\\.).*")
                && lower.matches(
                        ".*\\.(java|kt|swift|dart|ts|tsx|js|jsx|json|md|gradle|xml|yaml|yml|sql)");
    }
}
