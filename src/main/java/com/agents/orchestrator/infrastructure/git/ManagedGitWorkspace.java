package com.agents.orchestrator.infrastructure.git;

import com.agents.orchestrator.application.repository.PrepareWorkBranch;
import com.agents.orchestrator.application.repository.RepositorySource;
import com.agents.orchestrator.application.repository.RepositoryWorkspace;
import com.agents.orchestrator.infrastructure.process.BoundedProcess;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Dedicated clones with fast-forward updates and create-only remote branch publishing. */
public final class ManagedGitWorkspace implements RepositoryWorkspace, PrepareWorkBranch {
    private final Path workspaces;
    private final BoundedProcess process;
    private final Map<String, String> environment;
    private final boolean allowLocal;

    public ManagedGitWorkspace(
            Path workspaces,
            BoundedProcess process,
            Map<String, String> environment,
            boolean allowLocal) {
        this.workspaces = workspaces;
        this.process = process;
        this.environment = Map.copyOf(environment);
        this.allowLocal = allowLocal;
    }

    @Override
    public PreparedRepository prepare(String projectId, String repository, RepositorySource source)
            throws Exception {
        return prepare(projectId, repository, source, source.baseBranch(), false);
    }

    @Override
    public PreparedRepository prepareWorkBranch(
            String projectId, String repository, RepositorySource source, String branch)
            throws Exception {
        if (branch.equals(source.baseBranch()))
            throw new IllegalArgumentException("WORK_BRANCH_EQUALS_BASE");
        return prepare(projectId, repository, source, branch, true);
    }

    private PreparedRepository prepare(
            String projectId,
            String repository,
            RepositorySource source,
            String branch,
            boolean create)
            throws Exception {
        if (!projectId.matches("[a-z0-9-]{1,64}")
                || !repository.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*")) {
            throw new IllegalArgumentException("INVALID_WORKSPACE_ID");
        }
        var identity = GitRepositoryConfig.identity(source.cloneUrl(), allowLocal);
        var git = new GitSession();
        var key =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                (identity + "\n" + branch)
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)))
                        .substring(0, 24);
        var parent = workspaces.toAbsolutePath().resolve(projectId).resolve(repository);
        Files.createDirectories(parent);
        var root = parent.resolve(key);
        try (var channel =
                        FileChannel.open(
                                parent.resolve(key + ".lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("WORKSPACE_BUSY");
            git.run(parent, "check-ref-format", "--branch", source.baseBranch());
            git.run(parent, "check-ref-format", "--branch", branch);
            if (!Files.exists(root)) {
                var temporary = Files.createTempDirectory(parent, "clone-");
                try {
                    git.run(
                            parent,
                            "clone",
                            "--no-checkout",
                            "--no-recurse-submodules",
                            "--origin",
                            "origin",
                            "--",
                            source.cloneUrl(),
                            temporary.toString());
                    git.run(
                            temporary,
                            "checkout",
                            "--detach",
                            "refs/remotes/origin/" + source.baseBranch());
                    Files.writeString(
                            temporary.resolve(".git/orchestrator-prepared"), source.baseBranch());
                    Files.move(temporary, root, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    if (Files.exists(temporary)) {
                        try (var paths = Files.walk(temporary)) {
                            for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                                Files.deleteIfExists(path);
                        }
                    }
                }
            }
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root.resolve(".git")))
                throw new IllegalArgumentException("INVALID_MANAGED_WORKSPACE");
            var configuredRemote = git.run(root, "remote", "get-url", "origin").trim();
            if (!identity.equals(GitRepositoryConfig.identity(configuredRemote, allowLocal))) {
                throw new IllegalArgumentException("REPOSITORY_IDENTITY_MISMATCH");
            }
            if (!configuredRemote.equals(source.cloneUrl()))
                git.run(root, "remote", "set-url", "origin", source.cloneUrl());
            // Never discard edits, untracked files, or local commits from a previous execution.
            var marker = root.resolve(".git/orchestrator-prepared");
            if (!Files.exists(marker)) throw new IllegalArgumentException("UNMANAGED_WORKSPACE");
            if (!git.run(root, "status", "--porcelain", "--untracked-files=all").isBlank()) {
                throw new IllegalStateException("WORKSPACE_HAS_LOCAL_CHANGES");
            }
            git.run(
                    root,
                    "fetch",
                    "--prune",
                    "--no-tags",
                    "origin",
                    "+refs/heads/*:refs/remotes/origin/*");
            var remoteRefs =
                    git.run(root, "for-each-ref", "--format=%(refname)", "refs/remotes/origin/")
                            .lines()
                            .toList();
            var remote = "refs/remotes/origin/" + branch;
            if (!remoteRefs.contains(remote)) {
                if (!create) throw new IllegalArgumentException("REMOTE_BRANCH_NOT_FOUND");
                var revision =
                        git.run(
                                        root,
                                        "rev-parse",
                                        "--verify",
                                        "refs/remotes/origin/" + source.baseBranch() + "^{commit}")
                                .trim();
                // Empty expected lease means create only; never overwrite a concurrently created
                // branch.
                git.run(
                        root,
                        "push",
                        "--force-with-lease=refs/heads/" + branch + ":",
                        "origin",
                        revision + ":refs/heads/" + branch);
                git.run(
                        root,
                        "fetch",
                        "--no-tags",
                        "origin",
                        "+refs/heads/" + branch + ":" + remote);
            }
            var localRefs =
                    git.run(root, "for-each-ref", "--format=%(refname)", "refs/heads/")
                            .lines()
                            .toList();
            if (localRefs.contains("refs/heads/" + branch)) git.run(root, "checkout", branch);
            else git.run(root, "checkout", "--track", "-b", branch, "origin/" + branch);
            git.run(root, "pull", "--ff-only", "--no-rebase", "origin", "refs/heads/" + branch);
            var revision = git.run(root, "rev-parse", "HEAD").trim();
            var remoteRevision = git.run(root, "rev-parse", "refs/remotes/origin/" + branch).trim();
            if (!revision.equals(remoteRevision))
                throw new IllegalStateException("WORKSPACE_DIVERGED_FROM_REMOTE");
            Files.writeString(marker, branch);
            return new PreparedRepository(root, revision);
        }
    }

    private final class GitSession {
        private final long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();

        private String run(Path root, String... args) throws Exception {
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException("GIT_OPERATION_TIMEOUT");
            var command =
                    new ArrayList<>(
                            List.of(
                                    "git",
                                    "--no-pager",
                                    "-c",
                                    "core.fsmonitor=false",
                                    "-c",
                                    "core.hooksPath=/dev/null",
                                    "-c",
                                    "protocol.ext.allow=never",
                                    "-c",
                                    "protocol.file.allow=" + (allowLocal ? "always" : "never")));
            command.addAll(List.of(args));
            return process.run(command, root, Duration.ofNanos(remaining), 2_000_000, environment);
        }
    }

    public static Map<String, String> networkEnvironment() {
        var result = new HashMap<>(BoundedProcess.gitEnvironment());
        for (var key : List.of("HOME", "SSH_AUTH_SOCK", "TMPDIR"))
            if (System.getenv(key) != null) result.put(key, System.getenv(key));
        result.put("GIT_SSH_COMMAND", "ssh -o BatchMode=yes -o StrictHostKeyChecking=yes");
        var config = System.getenv("SOURCE_CONTROL_GIT_CONFIG");
        if (config != null && !config.isBlank()) result.put("GIT_CONFIG_GLOBAL", config);
        return result;
    }
}
