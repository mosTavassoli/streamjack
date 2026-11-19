package io.github.mostavassoli.streamjack.kafka.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CheckRunTargetTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void defaultsToTheGitHubApi() {
        CheckRunTarget target = CheckRunTarget.of(null, "mosTavassoli/streamjack", SHA, "streamjack");

        assertThat(target.checkRunsUri())
                .hasToString("https://api.github.com/repos/mosTavassoli/streamjack/check-runs");
    }

    @Test
    void supportsEnterpriseServerApiPaths() {
        CheckRunTarget target =
                CheckRunTarget.of("https://github.example.com/api/v3/", "octo/hello", SHA, "streamjack");

        assertThat(target.checkRunsUri())
                .hasToString("https://github.example.com/api/v3/repos/octo/hello/check-runs");
    }

    @Test
    void requiresHttpsExceptForLoopbackTestServers() {
        assertThatThrownBy(() -> CheckRunTarget.of("http://api.github.com", "octo/hello", SHA, "streamjack"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThat(CheckRunTarget.of("http://127.0.0.1:8080", "octo/hello", SHA, "streamjack").checkRunsUri())
                .hasToString("http://127.0.0.1:8080/repos/octo/hello/check-runs");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://user:secret@api.github.com", "https://api.github.com?x=1", "api.github.com"})
    void rejectsCredentialsQueriesAndRelativeApiUrls(String apiUrl) {
        assertThatThrownBy(() -> CheckRunTarget.of(apiUrl, "octo/hello", SHA, "streamjack"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"octo", "octo/hello/extra", "octo/..", "octo/.", "-octo/hello", "../hello", "octo/hel lo"})
    void rejectsInvalidRepositories(String repository) {
        assertThatThrownBy(() -> CheckRunTarget.of(null, repository, SHA, "streamjack"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc123", "0123456789ABCDEF0123456789ABCDEF01234567", "refs/heads/main"})
    void requiresFullCommitShas(String headSha) {
        assertThatThrownBy(() -> CheckRunTarget.of(null, "octo/hello", headSha, "streamjack"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("head SHA");
    }

    @Test
    void rejectsUnsafeCheckNames() {
        for (String name : new String[] {" ", "stream\njack", "x".repeat(101)}) {
            assertThatThrownBy(() -> CheckRunTarget.of(null, "octo/hello", SHA, name))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("check name");
        }
    }
}
