package io.github.mostavassoli.streamjack.kafka.cli;

import io.github.mostavassoli.streamjack.core.report.ReportException;
import io.github.mostavassoli.streamjack.core.report.ReportReader;
import io.github.mostavassoli.streamjack.core.report.RunReport;
import io.github.mostavassoli.streamjack.kafka.github.CheckRunOutput;
import io.github.mostavassoli.streamjack.kafka.github.CheckRunRenderer;
import io.github.mostavassoli.streamjack.kafka.github.CheckRunTarget;
import io.github.mostavassoli.streamjack.kafka.github.GitHubChecksClient;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "github-check",
        mixinStandardHelpOptions = true,
        version = Streamjack.VERSION,
        description = "Publish a streamjack JSON report as a GitHub check run.")
final class GitHubCheckCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = "--report", required = true, description = "streamjack JSON report to publish")
    private Path report;

    @Option(
            names = "--check-name",
            defaultValue = "streamjack",
            description = "Check run name (default: ${DEFAULT-VALUE})")
    private String checkName;

    @Option(names = "--repository", description = "Repository as owner/name (default: $GITHUB_REPOSITORY)")
    private String repository;

    @Option(
            names = "--head-sha",
            description = "Commit that receives the check (default: $GITHUB_SHA); use the pull request head SHA")
    private String headSha;

    @Option(
            names = "--summary-file",
            description = "Append the rendered Markdown to this file, such as $GITHUB_STEP_SUMMARY")
    private Path summaryFile;

    @Option(
            names = "--no-publish",
            description = "Only render the check; do not use GITHUB_TOKEN or contact GitHub")
    private boolean noPublish;

    @Option(
            names = "--enforce",
            description = "Exit 1 if the check fails; utility warnings do not fail the check")
    private boolean enforce;

    private final Map<String, String> environment;

    GitHubCheckCommand(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try {
            RunReport runReport = new ReportReader().read(report);
            CheckRunOutput output = new CheckRunRenderer().render(runReport);
            if (summaryFile != null) {
                appendSummary(markdown(output));
            }
            if (noPublish) {
                if (summaryFile == null) {
                    out.print(markdown(output));
                    out.flush();
                }
            } else {
                CheckRunTarget target = target();
                String token = token();
                String actionsRunId = Objects.equals(target.owner() + "/" + target.repository(),
                        environment.get("GITHUB_REPOSITORY")) ? environment.get("GITHUB_RUN_ID") : null;
                try (HttpClient http = GitHubChecksClient.defaultHttpClient()) {
                    new GitHubChecksClient(http).create(target, token, output, actionsRunId);
                }
                out.printf("Published streamjack check (%s)%n", output.conclusion());
            }
            return enforce && output.failed() ? 1 : 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("streamjack: interrupted while publishing the check run");
            return 2;
        } catch (ReportException e) {
            // Parser diagnostics can include untrusted text from the report. Do not echo them in CI logs.
            err.println("streamjack: cannot read report; expected a valid " + RunReport.REPORT_VERSION + " JSON report");
            return 2;
        } catch (RuntimeException | IOException e) {
            err.println("streamjack: " + e.getMessage());
            return 2;
        }
    }

    private CheckRunTarget target() {
        return CheckRunTarget.of(
                environment.get("GITHUB_API_URL"),
                required(repository, "GITHUB_REPOSITORY", "--repository"),
                required(headSha, "GITHUB_SHA", "--head-sha"),
                checkName);
    }

    private String token() {
        String token = environment.get("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("GITHUB_TOKEN is required to publish; use --no-publish to only render");
        }
        return token.strip();
    }

    private String required(String option, String variable, String optionName) {
        String value = option != null && !option.isBlank() ? option : environment.get(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(optionName + " or " + variable + " is required to publish");
        }
        return value.strip();
    }

    private static String markdown(CheckRunOutput output) {
        return "## streamjack: " + output.title() + "\n\n" + output.summary() + "\n" + output.text() + "\n";
    }

    private void appendSummary(String markdown) throws IOException {
        Path parent = summaryFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(summaryFile, markdown, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
