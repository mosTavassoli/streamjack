package io.github.mostavassoli.streamjack.kafka.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubChecksClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String TOKEN = "ghs_synthetic-test-token";

    private FakeGitHubApi api;
    private HttpClient http;
    private GitHubChecksClient client;

    @BeforeEach
    void start() throws Exception {
        api = new FakeGitHubApi();
        http = GitHubChecksClient.defaultHttpClient();
        client = new GitHubChecksClient(http);
    }

    @AfterEach
    void stop() {
        http.close();
        api.close();
    }

    @Test
    void createsACompletedCheckRun() throws Exception {
        CheckRunOutput output = new CheckRunOutput(true, "1 scenario: 1 compromised", "summary", "details");

        client.create(target(), TOKEN, output, null);

        FakeGitHubApi.Request request = api.singleRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/repos/octo/hello/check-runs");
        assertThat(request.header("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(request.header("Accept")).isEqualTo("application/vnd.github+json");
        assertThat(request.header("X-GitHub-Api-Version")).isEqualTo(GitHubChecksClient.API_VERSION);
        JsonNode body = JSON.readTree(request.body());
        assertThat(body.get("name").asText()).isEqualTo("streamjack");
        assertThat(body.get("head_sha").asText()).isEqualTo(SHA);
        assertThat(body.get("status").asText()).isEqualTo("completed");
        assertThat(body.get("conclusion").asText()).isEqualTo("failure");
        assertThat(body.at("/output/title").asText()).isEqualTo("1 scenario: 1 compromised");
        assertThat(body.at("/output/summary").asText()).isEqualTo("summary");
        assertThat(body.at("/output/text").asText()).isEqualTo("details");
    }

    @Test
    void explainsPermissionErrorsWithoutRevealingTheToken() {
        api.respond(403, "{\"message\":\"" + TOKEN + "\"}", Map.of());

        assertThatThrownBy(() -> client.create(target(), TOKEN, output(), null))
                .isInstanceOf(GitHubChecksClient.GitHubApiException.class)
                .hasMessageContaining("HTTP 403", "checks: write")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void doesNotFollowRedirects() {
        api.respond(307, "", Map.of("Location", api.url() + "/elsewhere"));

        assertThatThrownBy(() -> client.create(target(), TOKEN, output(), null))
                .isInstanceOf(GitHubChecksClient.GitHubApiException.class)
                .hasMessageContaining("HTTP 307");
        assertThat(api.requests()).singleElement()
                .extracting(FakeGitHubApi.Request::path)
                .isEqualTo("/repos/octo/hello/check-runs");
    }

    @Test
    void rejectsTokensThatCannotBeSentAsAHeaderWithoutEchoingThem() {
        assertThatThrownBy(() -> client.create(target(), "secret\nvalue", output(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret");
        assertThat(api.requests()).isEmpty();
    }

    private CheckRunTarget target() {
        return CheckRunTarget.of(api.url(), "octo/hello", SHA, "streamjack");
    }

    private static CheckRunOutput output() {
        return new CheckRunOutput(false, "title", "summary", "text");
    }
}
