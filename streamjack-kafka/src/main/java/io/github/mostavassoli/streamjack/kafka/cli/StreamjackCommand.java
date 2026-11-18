package io.github.mostavassoli.streamjack.kafka.cli;

import io.github.mostavassoli.streamjack.core.evaluation.ExecutionStatus;
import io.github.mostavassoli.streamjack.core.evaluation.ScenarioResult;
import io.github.mostavassoli.streamjack.core.evaluation.SecurityVerdict;
import io.github.mostavassoli.streamjack.core.evaluation.UtilityVerdict;
import io.github.mostavassoli.streamjack.core.report.ReportWriter;
import io.github.mostavassoli.streamjack.core.report.RunReport;
import io.github.mostavassoli.streamjack.core.scenario.Scenario;
import io.github.mostavassoli.streamjack.core.scenario.ScenarioLoader;
import io.github.mostavassoli.streamjack.kafka.KafkaScenarioRunner;
import io.github.mostavassoli.streamjack.kafka.RunnerConfiguration;
import io.github.mostavassoli.streamjack.kafka.ScenarioExecution;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "streamjack",
        mixinStandardHelpOptions = true,
        version = "streamjack 0.2.0-SNAPSHOT",
        description = "Test a Kafka-connected AI agent with versioned security scenarios.")
final class StreamjackCommand implements Callable<Integer> {

    @Option(
            names = "--bootstrap-servers",
            defaultValue = "localhost:9092",
            description = "Kafka bootstrap servers (default: ${DEFAULT-VALUE})")
    private String bootstrapServers;

    @Option(
            names = "--scenarios",
            defaultValue = "scenarios",
            description = "Scenario YAML file or directory (default: ${DEFAULT-VALUE})")
    private Path scenarios;

    @Option(names = "--agent", required = true, description = "Name of the agent under test")
    private String agent;

    @Option(
            names = "--report",
            defaultValue = "reports/streamjack-report.json",
            description = "JSON report path (default: ${DEFAULT-VALUE})")
    private Path report;

    @Option(
            names = "--timeout-seconds",
            defaultValue = "30",
            description = "Per-scenario observation timeout (default: ${DEFAULT-VALUE})")
    private long timeoutSeconds;

    @Option(
            names = "--quiet-period-millis",
            defaultValue = "1000",
            description = "Quiet period after all completion markers (default: ${DEFAULT-VALUE})")
    private long quietPeriodMillis;

    @Option(
            names = "--max-evidence-records",
            defaultValue = "10000",
            description = "Maximum correlated records per scenario (default: ${DEFAULT-VALUE})")
    private int maxEvidenceRecords;

    @Option(
            names = "--create-topics",
            negatable = true,
            defaultValue = "true",
            description = "Create missing single-partition test topics (default: ${DEFAULT-VALUE})")
    private boolean createTopics;

    @Option(
            names = "--kafka-properties",
            description = "Optional Java properties file for Kafka TLS/SASL client settings")
    private Path kafkaProperties;

    @Override
    public Integer call() {
        try {
            if (agent == null || agent.isBlank()) {
                throw new IllegalArgumentException("--agent must not be blank");
            }
            RunnerConfiguration configuration = new RunnerConfiguration(
                    bootstrapServers,
                    Duration.ofSeconds(timeoutSeconds),
                    Duration.ofMillis(quietPeriodMillis),
                    maxEvidenceRecords,
                    createTopics,
                    loadKafkaProperties());
            KafkaScenarioRunner runner = new KafkaScenarioRunner(configuration);
            List<Scenario> loaded = loadScenarios();
            if (loaded.isEmpty()) {
                throw new IllegalArgumentException("No scenario YAML files found at " + scenarios);
            }
            List<ScenarioResult> results = new ArrayList<>();
            for (Scenario scenario : loaded) {
                ScenarioExecution execution = runner.run(scenario);
                results.add(execution.result());
                print(execution);
            }
            RunReport runReport = RunReport.of(agent, results);
            new ReportWriter().write(runReport, report);
            System.out.println("Report: " + report.toAbsolutePath());
            return exitCode(results);
        } catch (RuntimeException | IOException e) {
            System.err.println("streamjack: " + e.getMessage());
            return 2;
        }
    }

    private List<Scenario> loadScenarios() {
        ScenarioLoader loader = new ScenarioLoader();
        if (Files.isDirectory(scenarios)) {
            return loader.loadDirectory(scenarios);
        }
        return List.of(loader.load(scenarios));
    }

    private Map<String, Object> loadKafkaProperties() throws IOException {
        if (kafkaProperties == null) {
            return Map.of();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(kafkaProperties)) {
            properties.load(input);
        }
        return properties.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().toString(), Map.Entry::getValue));
    }

    private static void print(ScenarioExecution execution) {
        ScenarioResult result = execution.result();
        System.out.printf(
                "%-7s  %-12s  %-12s  %-12s  %d ms%n",
                result.scenarioId(),
                result.execution(),
                result.security(),
                result.utility(),
                execution.duration().toMillis());
    }

    private static int exitCode(List<ScenarioResult> results) {
        if (results.stream().anyMatch(result -> result.execution() == ExecutionStatus.INCONCLUSIVE)) {
            return 2;
        }
        boolean failed = results.stream().anyMatch(result ->
                result.security() == SecurityVerdict.COMPROMISED || result.utility() == UtilityVerdict.NOT_MET);
        return failed ? 1 : 0;
    }
}
