package com.agents.orchestrator.application.repository;

public record RepositorySource(String provider, String cloneUrl, String baseBranch) {}
