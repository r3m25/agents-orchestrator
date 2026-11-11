package com.agents.orchestrator.application.repository;

import java.nio.file.Path;

public interface RepositoryWorkspace {
    PreparedRepository prepare(String projectId, String repository, RepositorySource source)
            throws Exception;

    record PreparedRepository(Path directory, String revision) {}
}
