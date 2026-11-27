package io.github.mostavassoli.streamjack.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Requires a packaged OpenAI agent and Docker. Every provider request goes to the loopback mock. */
@EnabledIfSystemProperty(named = "bench.agent-dir", matches = ".+")
class BenchMockIT {

    private static final String MODEL = "mock-model";
    private static final Path AGENT = Path.of(System.getProperty("bench.agent-dir", "."));

    @TempDir
    Path work;

    @Test
    void packagedAgentRunsAndScoresOneScenarioWithoutReplaying() throws Exception {
        try (var mock = new MockOpenAiServer(0, MockOpenAiServer.Behavior.NORMAL)) {
            List<Path> scenarios = Setup.scenarioFiles(Path.of(".."), List.of("sj-101"));
            var run = run(mock, scenarios, 1);
            assertThat(run.run()).isZero();
            assertThat(run.run()).isZero();
            assertThat(mock.served()).isEqualTo(2);
            assertThat(mock.unexpectedAuthorization()).isZero();
            assertThat(Scorer.scoreRun(work.resolve("run"))).singleElement().satisfies(row -> {
                assertThat(row.evidence()).isEqualTo("valid");
                assertThat(row.recommended()).isEqualTo("DRAFT");
                assertThat(row.published()).isFalse();
                assertThat(row.modelCalls()).isEqualTo(2);
                assertThat(row.inputTokens()).isEqualTo(2L * MockOpenAiServer.INPUT_TOKENS);
            });
        }
    }

    @ParameterizedTest
    @CsvSource({"WRONG_MODEL,4,2", "NO_USAGE,4,2", "SERVER_ERROR,5,4", "RATE_LIMIT,5,4"})
    void handlesProviderFailuresWithExpectedRequestCount(MockOpenAiServer.Behavior behavior, int exit, int requests)
            throws Exception {
        try (var mock = new MockOpenAiServer(0, behavior)) {
            List<Path> scenarios = Setup.scenarioFiles(Path.of(".."), List.of("sj-101"));
            // Three attempts are allowed, yet none of these outcomes may be retried: one attempt, then a halt.
            // Server errors and rate limits are sent twice per call by the client itself (max-retries=1).
            var run = run(mock, scenarios, 3);
            assertThat(run.run()).isEqualTo(exit);
            assertThat(mock.served()).isEqualTo(requests);
            mock.behavior(MockOpenAiServer.Behavior.NORMAL);
            assertThat(run.run()).isEqualTo(BenchRun.STOPPED);
            assertThat(mock.served()).isEqualTo(requests);
        }
    }

    private BenchRun run(MockOpenAiServer mock, List<Path> scenarios, int attempts) {
        var settings = Setup.settings(MODEL, mock.baseUrl(), 1, List.of("NEUTRAL"), 42, 1, 2, 0.12, scenarios, AGENT);
        return new BenchRun(new BenchRun.Options(settings, work.resolve("run"), Path.of(".."), scenarios, 1, attempts, 0),
                new DockerTrialExecutor(new DockerTrialExecutor.Agent(AGENT, MODEL, mock.baseUrl(), "never-send-this"),
                        Path.of("..")),
                new PrintWriter(new StringWriter()));
    }
}
