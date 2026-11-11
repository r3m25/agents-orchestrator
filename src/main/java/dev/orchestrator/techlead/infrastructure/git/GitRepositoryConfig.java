package dev.orchestrator.techlead.infrastructure.git;

import dev.orchestrator.techlead.application.repository.RepositorySource;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.Set;

public final class GitRepositoryConfig {
    private GitRepositoryConfig() { }

    public static RepositorySource resolve(JsonNode profile, JsonNode repository) {
        var control = profile.path("sourceControl");
        var provider = repository.path("sourceControl").path("provider").asText(control.path("provider").asText("git"));
        if (!Set.of("git", "bitbucket", "github", "gitlab").contains(provider)) {
            throw new IllegalArgumentException("UNSUPPORTED_SOURCE_CONTROL_PROVIDER");
        }
        var url = repository.path("cloneUrl").asText();
        if (url.isBlank() && provider.equals("bitbucket")) {
            var workspace = control.path("workspace").asText();
            var name = repository.path("repository").asText();
            if (!workspace.matches("[a-zA-Z0-9_-]+") || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*")) {
                throw new IllegalArgumentException("CLONE_URL_REQUIRED");
            }
            url = "git@bitbucket.org:" + workspace + "/" + name + ".git";
        }
        if (url.isBlank()) throw new IllegalArgumentException("CLONE_URL_REQUIRED");
        return new RepositorySource(provider, url, repository.path("baseBranch").asText());
    }

    public static String identity(String url, boolean allowLocal) {
        if (url.matches("[a-zA-Z0-9_.-]+@[a-zA-Z0-9_.-]+:[a-zA-Z0-9_./-]+")) {
            var split = url.substring(url.indexOf('@') + 1).split(":", 2);
            return split[0].toLowerCase(java.util.Locale.ROOT) + "/" + trimSuffix(split[1]);
        }
        try {
            var uri = URI.create(url);
            if (allowLocal && uri.getScheme().equals("file") && uri.getAuthority() == null) return uri.normalize().toString();
            if (!Set.of("ssh", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getQuery() != null || uri.getFragment() != null || uri.getPath().isBlank()
                    || (uri.getUserInfo() != null && (!uri.getScheme().equals("ssh") || uri.getUserInfo().contains(":")))) {
                throw new IllegalArgumentException("INVALID_CLONE_URL");
            }
            return uri.getHost().toLowerCase(java.util.Locale.ROOT) + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                    + "/" + trimSuffix(uri.getPath().replaceFirst("^/", ""));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("INVALID_CLONE_URL");
        }
    }

    private static String trimSuffix(String path) { return path.replaceFirst("\\.git$", ""); }
}
