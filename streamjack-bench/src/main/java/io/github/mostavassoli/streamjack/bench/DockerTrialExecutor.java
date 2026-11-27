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
    private final Path repository;

    /** @param repository the repository root that the manifest's scenario paths are relative to */
    public DockerTrialExecutor(Agent agent, Path repository) {
        this.agent = agent;
        this.repository = repository.toAbsolutePath().normalize();
    }

    @Override
    public AttemptStatus execute(Trial trial, Path attemptDir) {
        String started = Instant.now().toString();
        KafkaContainer broker = null;
        Process process = null;
        boolean scenarioStarted = false;
        AttemptStatus status;
        try {
            broker = startBroker();
            process = startAgent(trial, attemptDir, broker.getBootstrapServers());
            if (!waitUntilStarted(process, attemptDir.resolve("agent.log"))) {
                status = AttemptStatus.of(Outcome.SETUP_FAILED, "the agent did not start", trial.events());
            } else {
                scenarioStarted = true;
                ScenarioExecution execution = new KafkaScenarioRunner(new RunnerConfiguration(
                        broker.getBootstrapServers(), SCENARIO_TIMEOUT, Duration.ofSeconds(1), 10_000, true, Map.of()))
                        .run(new ScenarioLoader().load(repository.resolve(trial.scenarioFile())));
                ScenarioResult result = execution.result();
                new ReportWriter().write(RunReport.of("openai-" + agent.model() + "-" + trial.mode(),
                        List.of(result)), attemptDir.resolve("report.json"));
                Path evidence = new EvidenceWriter().write(execution, attemptDir.resolve("evidence"));
                // The usage log is complete only once the agent has exited.
                Process stopping = process;
                process = null;
                status = stopAgent(stopping)
                        ? AttemptClassifier.classify(trial, result, Files.exists(evidence), Usage.read(attemptDir))
                        : notExited(stopping, trial);
            }
        } catch (RuntimeException | IOException e) {
            // Before the scenario starts no request can have been sent, so the attempt can be retried; afterwards a
            // request may have been sent, so its outcome is unknown. A logged provider error is applied by
            // AttemptClassifier.reconcile in either case.
            Outcome outcome = scenarioStarted ? Outcome.UNKNOWN : Outcome.SETUP_FAILED;
            status = AttemptStatus.of(outcome, "harness error: " + e.getClass().getSimpleName(), trial.events());
        } finally {
            if (process != null && !stopAgent(process)) {
                // An agent that may still be running could still send requests with the key: halt everything.
                status = notExited(process, trial);
            }
            if (broker != null) {
                try {
                    broker.stop();
                } catch (RuntimeException e) {
                    // Cleanup must not discard the attempt's result; the container is removed by Testcontainers.
                }
            }
        }
        return finish(status, started);
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
        // An allowlist, not a blocklist: configuration can arrive under many names (for example Quarkus profile
        // variables such as _PROD_QUARKUS_..._BASE_URL), and any of them could change the endpoint that receives the
        // key or the experiment's settings without changing the fingerprinted build.
        Map<String, String> inherited = new java.util.HashMap<>(environment);
        environment.clear();
        inherited.forEach((name, value) -> {
            if (inherited(name)) {
                environment.put(name, value);
            }
        });
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

    /** Operating-system variables the agent's JVM may inherit; everything else is dropped. */
    static final java.util.Set<String> ALLOWED_ENVIRONMENT = java.util.Set.of(
            // Windows
            "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "COMSPEC", "PATH", "PATHEXT", "TEMP", "TMP", "USERPROFILE",
            "USERNAME", "LOCALAPPDATA", "APPDATA", "HOMEDRIVE", "HOMEPATH", "PROCESSOR_ARCHITECTURE",
            "NUMBER_OF_PROCESSORS",
            // Linux and macOS
            "HOME", "USER", "LOGNAME", "TMPDIR", "LANG", "LC_ALL", "LC_CTYPE", "TZ");

    static boolean inherited(String name) {
        return ALLOWED_ENVIRONMENT.contains(name.toUpperCase(java.util.Locale.ROOT));
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

    /**
     * The agent process did not confirm its exit: its usage log may be incomplete and it may still hold the key and
     * send requests. Halting (STOPPED), with the process id for inspection.
     */
    private static AttemptStatus notExited(Process process, Trial trial) {
        return AttemptStatus.of(Outcome.STOPPED, "the agent process (pid " + process.pid() + ") did not exit",
                trial.events());
    }

    /** Stops the agent and returns whether it has exited (only then is its usage log complete). */
    private static boolean stopAgent(Process process) {
        try {
            process.destroy();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                return process.destroyForcibly().waitFor(30, TimeUnit.SECONDS);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return false;
        } catch (RuntimeException e) {
            // Termination itself failed: treat it as an unconfirmed exit.
            return false;
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
