package io.github.mostavassoli.streamjack.bench;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import io.github.mostavassoli.streamjack.core.evaluation.ScenarioResult;
import io.github.mostavassoli.streamjack.core.report.ReportWriter;
import io.github.mostavassoli.streamjack.core.report.RunReport;
import io.github.mostavassoli.streamjack.core.scenario.ScenarioLoader;
import io.github.mostavassoli.streamjack.kafka.EvidenceWriter;
import io.github.mostavassoli.streamjack.kafka.KafkaScenarioRunner;
import io.github.mostavassoli.streamjack.kafka.RunnerConfiguration;
import io.github.mostavassoli.streamjack.kafka.ScenarioExecution;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Runs an attempt with a fresh Kafka broker (a container published on loopback only) and a fresh agent process, so no
 * memory, deduplication state, or leftover records carry over between trials. The scenario runs in-process with the
 * project's runner, which writes the report and the raw evidence.
 */
public final class DockerTrialExecutor implements TrialExecutor {

    static final String KAFKA_IMAGE = "apache/kafka:4.1.1";
    static final List<String> TOPICS = List.of("support.tickets", "support.triage", "support.published", "streamjack.audit");
    private static final Duration AGENT_START = Duration.ofSeconds(90);
    private static final Duration SCENARIO_TIMEOUT = Duration.ofSeconds(240);

    /**
     * @param baseUrl    a local mock endpoint, or null for the real API
     * @param apiKey     passed to the agent's environment only; never logged or written
     */
    public record Agent(Path dir, String model, String baseUrl, String apiKey) {

        public Agent {
            // A caller cannot send a real key to a mock.
            if (baseUrl != null) {
                apiKey = Setup.apiKey(baseUrl, null, null, null);
            }
        }

        @Override
        public String toString() {
            return "Agent[dir=" + dir + ", model=" + model + ", baseUrl=" + baseUrl + ", apiKey=<redacted>]";
        }
    }

    private final Agent agent;

    public DockerTrialExecutor(Agent agent) {
        this.agent = agent;
    }

    @Override
    public AttemptStatus execute(Trial trial, Path attemptDir) {
        String started = Instant.now().toString();
        KafkaContainer broker = null;
        Process process = null;
        boolean scenarioStarted = false;
        try {
            broker = startBroker();
            process = startAgent(trial, attemptDir, broker.getBootstrapServers());
            if (!waitUntilStarted(process, attemptDir.resolve("agent.log"))) {
                return finish(AttemptStatus.of(Outcome.SETUP_FAILED, "the agent did not start", trial.events()), started);
            }
            scenarioStarted = true;
            ScenarioExecution execution = new KafkaScenarioRunner(new RunnerConfiguration(
                    broker.getBootstrapServers(), SCENARIO_TIMEOUT, Duration.ofSeconds(1), 10_000, true, Map.of()))
                    .run(new ScenarioLoader().load(Path.of(trial.scenarioFile())));
            ScenarioResult result = execution.result();
            new ReportWriter().write(RunReport.of("openai-" + agent.model() + "-" + trial.mode(),
                    List.of(result)), attemptDir.resolve("report.json"));
            Path evidence = new EvidenceWriter().write(execution, attemptDir.resolve("evidence"));
            // Let the agent finish writing its usage log before it is read.
            stopAgent(process);
            process = null;
            return finish(AttemptClassifier.classify(trial, result, Files.exists(evidence), Usage.read(attemptDir)), started);
        } catch (RuntimeException | IOException e) {
            // Before the scenario starts no request can have been sent, so the attempt can be retried; afterwards a
            // request may have been sent, so its outcome is unknown.
            Outcome outcome = scenarioStarted ? Outcome.UNKNOWN : Outcome.SETUP_FAILED;
            return finish(AttemptStatus.of(outcome, "harness error: " + e.getClass().getSimpleName(), trial.events()), started);
        } finally {
            if (process != null) {
                stopAgent(process);
            }
            if (broker != null) {
                broker.stop();
            }
        }
    }

    private static KafkaContainer startBroker() throws IOException {
        KafkaContainer broker = new KafkaContainer(KAFKA_IMAGE)
                // The listener is unauthenticated, so publish it on loopback only (on a random port).
                .withCreateContainerCmdModifier(command -> {
                    Ports ports = new Ports();
                    ports.bind(ExposedPort.tcp(9092), Ports.Binding.bindIp("127.0.0.1"));
                    command.getHostConfig().withPortBindings(ports);
                });
        broker.start();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers()))) {
            admin.createTopics(TOPICS.stream().map(topic -> new NewTopic(topic, 1, (short) 1)).toList())
                    .all().get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            broker.stop();
            throw new IOException("Could not create topics", e);
        }
        return broker;
    }

    private Process startAgent(Trial trial, Path attemptDir, String bootstrapServers) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path jar = agent.dir().toAbsolutePath().resolve("quarkus-run.jar");
        ProcessBuilder builder = new ProcessBuilder(java, "-Dquarkus.http.port=0", "-jar", jar.toString())
                .directory(attemptDir.toFile());
        Map<String, String> environment = builder.environment();
        // Runtime overrides would change the experiment without changing the fingerprinted build.
        environment.keySet().removeIf(DockerTrialExecutor::isConfigurationOverride);
        environment.put("KAFKA_BOOTSTRAP_SERVERS", bootstrapServers);
        environment.put("STREAMJACK_AGENT_MODEL", "OPENAI");
        environment.put("STREAMJACK_AGENT_SECURITY_MODE", trial.mode());
        environment.put("STREAMJACK_USAGE_LOG", attemptDir.resolve(Usage.FILE).toAbsolutePath().toString());
        environment.put("OPENAI_MODEL", agent.model());
        if (agent.apiKey() != null) {
            environment.put("OPENAI_API_KEY", agent.apiKey());
        }
        if (agent.baseUrl() != null) {
            environment.put("OPENAI_BASE_URL", agent.baseUrl());
        }
        builder.redirectOutput(attemptDir.resolve("agent.log").toFile());
        builder.redirectError(attemptDir.resolve("agent.err.log").toFile());
        return builder.start();
    }

    static boolean isConfigurationOverride(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("QUARKUS_") || upper.startsWith("MP_") || upper.startsWith("SMALLRYE_")
                || upper.startsWith("OPENAI_") || upper.startsWith("STREAMJACK_") || upper.startsWith("KAFKA_")
                || List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").contains(upper);
    }

    private static boolean waitUntilStarted(Process process, Path log) throws IOException {
        long deadline = System.nanoTime() + AGENT_START.toNanos();
        while (System.nanoTime() < deadline && process.isAlive()) {
            if (Files.exists(log) && Files.readString(log).contains("started in")) {
                return true;
            }
            sleep(Duration.ofMillis(500));
        }
        return false;
    }

    private static void stopAgent(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(30, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static AttemptStatus finish(AttemptStatus status, String started) {
        return new AttemptStatus(status.outcome(), status.reason(), status.events(), 0, started, Instant.now().toString());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
