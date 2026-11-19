package io.github.mostavassoli.streamjack.kafka.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mostavassoli.streamjack.core.check.Category;
import io.github.mostavassoli.streamjack.core.check.Finding;
import io.github.mostavassoli.streamjack.core.evaluation.ScenarioResult;
import io.github.mostavassoli.streamjack.core.evaluation.SecurityVerdict;
import io.github.mostavassoli.streamjack.core.evaluation.UtilityVerdict;
import io.github.mostavassoli.streamjack.core.report.ReportWriter;
import io.github.mostavassoli.streamjack.core.report.RunReport;
import io.github.mostavassoli.streamjack.kafka.github.FakeGitHubApi;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class GitHubCheckCommandTest {

    private static final String HEAD_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String MERGE_SHA = "fedcba9876543210fedcba9876543210fedcba98";
    private static final String TOKEN = "ghs_synthetic-test-token";

    @TempDir
    Path dir;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @Test
    void appendsTheJobSummaryWithoutPublishing() throws Exception {
        Path summary = dir.resolve("summary.md");
        Files.writeString(summary, "existing\n");

        int exitCode = execute(Map.of(),
                "github-check", "--report", writeReport().toString(), "--no-publish", "--summary-file", summary.toString());

        assertThat(exitCode).as(err.toString()).isZero();
        assertThat(Files.readString(summary))
                .startsWith("existing\n## streamjack: Security and utility passed\n")
                .contains("| SJ-001 | COMPLETED | SECURE | MET |");
    }

    @Test
    void enforcementFailsForInconclusiveEvidenceButNotForUtilityWarnings() {
        Path report = writeReport(ScenarioResult.inconclusive("SJ-001", "One", "missing terminal event"));
        assertThat(execute(Map.of(), "github-check", "--report", report.toString(), "--no-publish", "--enforce"))
                .isEqualTo(1);
        assertThat(out.toString()).contains("Security gate failed");

        writeReport(new ScenarioResult("SJ-001", "One", SecurityVerdict.SECURE, UtilityVerdict.NOT_MET,
                List.of(Finding.fail("utility", Category.UTILITY, "missing triage"))));
        assertThat(execute(Map.of(), "github-check", "--report", report.toString(), "--no-publish", "--enforce"))
                .isZero();
        assertThat(out.toString()).contains("Security gate passed with utility warnings");
    }

    @Test
    void publishesWithTheWorkflowEnvironmentAndPullRequestHeadSha() throws Exception {
        try (FakeGitHubApi api = new FakeGitHubApi()) {
            Map<String, String> environment = Map.of(
                    "GITHUB_API_URL", api.url(),
                    "GITHUB_REPOSITORY", "octo/hello",
                    "GITHUB_SHA", MERGE_SHA,
                    "GITHUB_TOKEN", TOKEN + "\n");

            int exitCode = execute(environment,
                    "github-check", "--report", writeReport().toString(), "--head-sha", HEAD_SHA);

            assertThat(exitCode).as(err.toString()).isZero();
            FakeGitHubApi.Request request = api.singleRequest();
            assertThat(request.path()).isEqualTo("/repos/octo/hello/check-runs");
            assertThat(request.header("Authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(new ObjectMapper().readTree(request.body()).get("head_sha").asText()).isEqualTo(HEAD_SHA);
            assertThat(out.toString()).contains("Published streamjack check (success)");
        }
    }

    @Test
    void refusesToPublishWithoutAToken() {
        int exitCode = execute(Map.of("GITHUB_REPOSITORY", "octo/hello", "GITHUB_SHA", HEAD_SHA),
                "github-check", "--report", writeReport().toString());

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("GITHUB_TOKEN is required");
    }

    @Test
    void rejectsReportsThatViolateTheContract() throws Exception {
        Path report = dir.resolve("report.json");
        Files.writeString(report, """
                {"reportVersion":"streamjack.dev/report/v1alpha1"}
                """);

        int exitCode = execute(Map.of(), "github-check", "--report", report.toString(), "--no-publish");

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("cannot read report", RunReport.REPORT_VERSION);
    }

    @Test
    void scenarioRunsStillRequireAnAgent() {
        int exitCode = execute(Map.of());

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("--agent is required");
    }

    private int execute(Map<String, String> environment, String... args) {
        CommandLine commandLine = Streamjack.commandLine(environment);
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(err, true));
        return commandLine.execute(args);
    }

    private Path writeReport() {
        return writeReport(new ScenarioResult("SJ-001", "One", SecurityVerdict.SECURE, UtilityVerdict.MET,
                List.of(Finding.pass("utility", Category.UTILITY, "met"))));
    }

    private Path writeReport(ScenarioResult result) {
        Path report = dir.resolve("report.json");
        new ReportWriter().write(RunReport.of("sample-agent-hardened", List.of(result)), report);
        return report;
    }
}
