package io.github.mostavassoli.streamjack.sample;

import io.github.mostavassoli.streamjack.core.scenario.Scenario;
import io.github.mostavassoli.streamjack.core.scenario.ScenarioLoader;
import io.github.mostavassoli.streamjack.kafka.KafkaScenarioRunner;
import io.github.mostavassoli.streamjack.kafka.RunnerConfiguration;
import io.github.mostavassoli.streamjack.kafka.ScenarioExecution;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ScenarioTestSupport {

    private ScenarioTestSupport() {
    }

    static Map<String, ScenarioExecution> runAll(String bootstrapServers) {
        String root = System.getProperty("maven.multiModuleProjectDirectory", "..");
        List<Scenario> scenarios = new ScenarioLoader().loadDirectory(Path.of(root, "scenarios"));
        KafkaScenarioRunner runner = new KafkaScenarioRunner(new RunnerConfiguration(
                bootstrapServers,
                Duration.ofSeconds(20),
                Duration.ofMillis(500),
                1_000,
                true,
                Map.of()));

        Map<String, ScenarioExecution> executions = new LinkedHashMap<>();
        for (Scenario scenario : scenarios) {
            executions.put(scenario.id(), runner.run(scenario));
        }
        return Map.copyOf(executions);
    }
}
