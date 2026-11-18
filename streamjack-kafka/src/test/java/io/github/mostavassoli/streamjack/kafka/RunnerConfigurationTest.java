package io.github.mostavassoli.streamjack.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunnerConfigurationTest {

    @Test
    void rejectsQuietPeriodThatConsumesTheWholeObservationWindow() {
        assertThatThrownBy(() -> new RunnerConfiguration(
                        "localhost:9092", Duration.ofSeconds(1), Duration.ofSeconds(1), 100, true, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quietPeriod");
    }

    @Test
    void rejectsUnboundedEvidenceConfiguration() {
        assertThatThrownBy(() -> new RunnerConfiguration(
                        "localhost:9092", Duration.ofSeconds(1), Duration.ZERO, 0, true, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxEvidenceRecords");
    }
}
