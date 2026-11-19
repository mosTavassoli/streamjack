package io.github.mostavassoli.streamjack.kafka.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

public final class GitHubChecksClient {

    static final String API_VERSION = "2026-03-10";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern TOKEN = Pattern.compile("[!-~]{1,4096}");
    private static final Pattern RUN_ID = Pattern.compile("[0-9]{1,20}");

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public GitHubChecksClient(HttpClient http) {
        this.http = http;
    }

    public static HttpClient defaultHttpClient() {
        // Redirects are never followed so the token cannot be forwarded to another host.
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void create(CheckRunTarget target, String token, CheckRunOutput output, String actionsRunId)
            throws IOException, InterruptedException {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("GitHub token is missing or contains characters not allowed in a header");
        }
        HttpRequest request = HttpRequest.newBuilder(target.checkRunsUri())
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("User-Agent", "streamjack")
                .header("X-GitHub-Api-Version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body(target, output, actionsRunId)))
                .build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 201) {
            throw new GitHubApiException(response.statusCode());
        }
    }

    private byte[] body(CheckRunTarget target, CheckRunOutput output, String actionsRunId) throws IOException {
        ObjectNode body = json.createObjectNode()
                .put("name", target.checkName())
                .put("head_sha", target.headSha())
                .put("status", "completed")
                .put("conclusion", output.conclusion());
        if ("api.github.com".equals(target.apiUrl().getHost())
                && actionsRunId != null && RUN_ID.matcher(actionsRunId).matches()) {
            body.put("details_url", "https://github.com/" + target.owner() + "/" + target.repository()
                    + "/actions/runs/" + actionsRunId);
        }
        body.putObject("output")
                .put("title", output.title())
                .put("summary", output.summary())
                .put("text", output.text());
        return json.writeValueAsBytes(body);
    }

    public static final class GitHubApiException extends IOException {

        private static final long serialVersionUID = 1L;

        GitHubApiException(int statusCode) {
            super("GitHub API returned HTTP " + statusCode + hint(statusCode));
        }

        private static String hint(int statusCode) {
            return switch (statusCode) {
                case 401 -> " (check that GITHUB_TOKEN is a valid workflow or GitHub App token)";
                case 403, 404 -> " (the workflow needs 'checks: write'; pull requests from forks get a read-only token)";
                case 422 -> " (confirm that the target commit is in the repository being checked)";
                default -> "";
            };
        }
    }
}
