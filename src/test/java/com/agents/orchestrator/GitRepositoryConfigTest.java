package com.agents.orchestrator;

import static org.junit.jupiter.api.Assertions.*;

import com.agents.orchestrator.infrastructure.git.GitRepositoryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitRepositoryConfigTest {
    final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"bitbucket", "github", "gitlab", "git"})
    void acceptsExplicitCloneUrlForConfiguredProvider(String provider) throws Exception {
        var profile = json.readTree("{\"sourceControl\":{\"provider\":\"" + provider + "\"}}");
        var repository =
                json.readTree(
                        "{\"repository\":\"api\",\"cloneUrl\":\"ssh://git@code.example.org/team/api.git\",\"baseBranch\":\"develop\"}");
        var source = GitRepositoryConfig.resolve(profile, repository);
        assertEquals(provider, source.provider());
        assertEquals("ssh://git@code.example.org/team/api.git", source.cloneUrl());
        assertEquals("develop", source.baseBranch());
    }

    @Test
    void keepsExistingBitbucketProfileCompatible() throws Exception {
        var source =
                GitRepositoryConfig.resolve(
                        json.readTree(
                                "{\"sourceControl\":{\"provider\":\"bitbucket\",\"workspace\":\"team\"}}"),
                        json.readTree("{\"repository\":\"api\",\"baseBranch\":\"main\"}"));
        assertEquals("git@bitbucket.org:team/api.git", source.cloneUrl());
    }

    @Test
    void normalizesSshAndHttpsIdentitiesForOfflineClones() {
        assertEquals(
                GitRepositoryConfig.identity("git@github.com:team/api.git", false),
                GitRepositoryConfig.identity("https://github.com/team/api.git", false));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://user:secret@code.example.org/team/api.git",
                "http://code.example.org/team/api.git",
                "ext::unsafe",
                "https://code.example.org/team/api.git?token=secret"
            })
    void rejectsCredentialBearingOrUnsafeRemoteUrl(String url) {
        assertThrows(
                IllegalArgumentException.class, () -> GitRepositoryConfig.identity(url, false));
    }
}
