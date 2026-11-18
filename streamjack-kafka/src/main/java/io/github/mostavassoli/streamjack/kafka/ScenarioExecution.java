package io.github.mostavassoli.streamjack.kafka;

import io.github.mostavassoli.streamjack.core.evaluation.ScenarioResult;
import io.github.mostavassoli.streamjack.core.evidence.Evidence;
import java.time.Duration;

public record ScenarioExecution(String runId, Duration duration, Evidence evidence, ScenarioResult result) {
}
