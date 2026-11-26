package io.github.mostavassoli.streamjack.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the packaged harness exactly as documented for the first paid trial (the shaded jar, its command line, from
 * the repository root), but against the loopback mock. The environment is deliberately poisoned with a real-looking
 * key and configuration overrides; none of them may reach the agent. Requires a packaged OpenAI agent and Docker.
 */
@EnabledIfSystemProperty(named = "bench.agent-dir", matches = ".+")
class BenchCliIT {

    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
    private static final Path AGENT = Path.of(System.getProperty("bench.agent-dir", ".")).toAbsolutePath();

    @TempDir
    Path work;

    @Test
    void documentedSmokeCommandRunsOnceScoresAndDoesNotReplay() throws Exception {
        try (var mock = new MockOpenAiServer(0, MockOpenAiServer.Behavior.NORMAL)) {
            Path run = work.resolve("spend").resolve("smoke");
            List<String> smoke = List.of("run", "--model", "mock-model", "--agent-dir", AGENT.toString(),
                    "--out", run.toString(), "--replicates", "1", "--modes", "NEUTRAL", "--scenario", "sj-101",
                    "--max-attempts", "1", "--max-trials", "1", "--input-price", "0.10", "--output-price", "0.50",
                    "--cost-limit", "0.20", "--openai-base-url", mock.baseUrl());

            assertThat(cli(smoke)).isZero();
            assertThat(mock.served()).isEqualTo(2);
            assertThat(mock.unexpectedAuthorization()).as("only the dummy key reaches the mock").isZero();

            // Running the same command again resumes a finished run: no new request.
            assertThat(cli(smoke)).isZero();
            assertThat(mock.served()).isEqualTo(2);

            assertThat(cli(List.of("score", "--run", run.toString()))).isZero();
            assertThat(run.resolve("scores.json")).exists();
            Path report = work.resolve("analysis.md");
            assertThat(cli(List.of("analyze", "--scores", run.resolve("scores.json").toString(),
                    "--out", report.toString()))).isZero();
            assertThat(Files.readString(report)).contains("## Calibration", "mock-model");
        }
    }

    private int cli(List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", shadedJar().toString()));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(REPOSITORY.toFile());
        Map<String, String> environment = builder.environment();
        // None of these may reach the agent: a real-looking key, an endpoint override under a Quarkus profile name
        // (pointing at a closed port), and a changed output cap.
        environment.put("OPENAI_API_KEY", "sk-test-this-must-never-reach-the-mock");
        environment.put("_PROD_QUARKUS_LANGCHAIN4J_OPENAI_BASE_URL", "http://127.0.0.1:9/v1/");
        environment.put("QUARKUS_LANGCHAIN4J_OPENAI_CHAT_MODEL_MAX_COMPLETION_TOKENS", "5");
        Path output = Files.createTempFile(work, "cli", ".log");
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = builder.start();
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("CLI timed out: " + Files.readString(output, StandardCharsets.UTF_8));
        }
        System.out.println(Files.readString(output, StandardCharsets.UTF_8));
        return process.exitValue();
    }

    private static Path shadedJar() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("target"))) {
            return files.filter(file -> file.getFileName().toString().matches("streamjack-bench-.*-all\\.jar"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Package the module first: no shaded jar in target"))
                    .toAbsolutePath();
        }
    }
}
