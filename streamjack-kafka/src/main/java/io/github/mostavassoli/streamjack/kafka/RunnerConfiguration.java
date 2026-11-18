package io.github.mostavassoli.streamjack.kafka;

import java.time.Duration;
import java.util.Map;

public record RunnerConfiguration(
        String bootstrapServers,
        Duration observationTimeout,
        Duration quietPeriod,
        int maxEvidenceRecords,
        boolean createTopics,
        Map<String, Object> kafkaProperties) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_QUIET_PERIOD = Duration.ofSeconds(1);
    public static final int DEFAULT_MAX_EVIDENCE_RECORDS = 10_000;

    public RunnerConfiguration {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("bootstrapServers is required");
        }
        if (observationTimeout == null || observationTimeout.isNegative() || observationTimeout.isZero()) {
            throw new IllegalArgumentException("observationTimeout must be positive");
        }
        if (quietPeriod == null || quietPeriod.isNegative()) {
            throw new IllegalArgumentException("quietPeriod must not be negative");
        }
        if (quietPeriod.compareTo(observationTimeout) >= 0) {
            throw new IllegalArgumentException("quietPeriod must be shorter than observationTimeout");
        }
        if (maxEvidenceRecords < 1) {
            throw new IllegalArgumentException("maxEvidenceRecords must be positive");
        }
        kafkaProperties = kafkaProperties == null ? Map.of() : Map.copyOf(kafkaProperties);
    }

    public static RunnerConfiguration defaults(String bootstrapServers) {
        return new RunnerConfiguration(
                bootstrapServers,
                DEFAULT_TIMEOUT,
                DEFAULT_QUIET_PERIOD,
                DEFAULT_MAX_EVIDENCE_RECORDS,
                true,
                Map.of());
    }
}
