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
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "streamjack",
        mixinStandardHelpOptions = true,
        version = Streamjack.VERSION,
        description = "Test a Kafka-connected AI agent with versioned security scenarios.")
final class StreamjackCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

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

    // Validated in call(): a picocli-required option here would also be demanded by subcommands.
    @Option(names = "--agent", description = "Name of the agent under test (required to run scenarios)")
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
        PrintWriter out = spec.commandLine().getOut();
        try {
            if (agent == null || agent.isBlank()) {
                throw new IllegalArgumentException("--agent is required");
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
                print(out, execution);
            }
            RunReport runReport = RunReport.of(agent, results);
            new ReportWriter().write(runReport, report);
            out.println("Report: " + report.toAbsolutePath());
            return exitCode(results);
        } catch (RuntimeException | IOException e) {
            spec.commandLine().getErr().println("streamjack: " + e.getMessage());
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

    private static void print(PrintWriter out, ScenarioExecution execution) {
        ScenarioResult result = execution.result();
        out.printf(
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
