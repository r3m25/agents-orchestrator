package com.agents.orchestrator.application.repository;

public interface PrepareWorkBranch {
    RepositoryWorkspace.PreparedRepository prepareWorkBranch(
            String projectId, String repository, RepositorySource source, String branch)
            throws Exception;
}
