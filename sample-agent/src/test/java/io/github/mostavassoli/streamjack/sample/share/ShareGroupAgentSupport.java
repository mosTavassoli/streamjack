package io.github.mostavassoli.streamjack.sample.share;

import io.github.mostavassoli.streamjack.core.protocol.AgentProtocol;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringSerializer;

/** Sends one synthetic ticket to the sample agent and counts how many times the slow model analyzes it. */
final class ShareGroupAgentSupport {

    private ShareGroupAgentSupport() {
    }

    /** Switches the sample agent's ticket channel to a share group, renewing frequently relative to the short lock. */
    abstract static class Profile implements QuarkusTestProfile {

        abstract long modelDelayMs();

        /** {@code null} keeps SmallRye's default. */
        abstract String unprocessedRecordMaxAgeMs();

        /** SmallRye renews on this interval; {@code null} keeps its default (5000 ms). */
        String autoCommitIntervalMs() {
            return "500";
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> config = new HashMap<>(Map.of(
                    "streamjack.agent.model", "SCRIPTED",
                    "streamjack.agent.security-mode", "HARDENED",
                    "streamjack.test.model-delay-ms", Long.toString(modelDelayMs()),
                    "mp.messaging.incoming.tickets.share-group.enabled", "true"));
            if (autoCommitIntervalMs() != null) {
                config.put("mp.messaging.incoming.tickets.auto.commit.interval.ms", autoCommitIntervalMs());
            }
            if (unprocessedRecordMaxAgeMs() != null) {
                config.put("mp.messaging.incoming.tickets.share-group.unprocessed-record-max-age.ms",
                        unprocessedRecordMaxAgeMs());
            }
            return config;
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(SlowScriptedModel.class);
        }
    }

    /**
     * What happened to one ticket: analyses started and completed, and whether processing settled (started at least
     * once, every started analysis completed, and nothing changed for the quiet period) before the time limit.
     */
    record Observation(int started, int completed, boolean settled) {

        @Override
        public String toString() {
            return "started=" + started + " completed=" + completed + (settled ? " (settled)" : " (not settled in time)");
        }
    }

    static Observation analysesOf(String bootstrapServers, String ticketId, Duration quiet) throws Exception {
        return analysesOf(bootstrapServers, ticketId, quiet, Duration.ofSeconds(120));
    }

    /** Sends one ticket and observes its analyses until they settle or {@code maxWait} elapses. */
    static Observation analysesOf(String bootstrapServers, String ticketId, Duration quiet, Duration maxWait)
            throws Exception {
        SlowScriptedModel.CALLS.remove(ticketId);
        SlowScriptedModel.COMPLETED.remove(ticketId);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", bootstrapServers,
                "key.serializer", StringSerializer.class.getName(),
                "value.serializer", StringSerializer.class.getName()))) {
            RecordHeaders headers = new RecordHeaders();
            headers.add(AgentProtocol.RUN_ID_HEADER, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            headers.add(AgentProtocol.EVENT_ID_HEADER, "e1".getBytes(StandardCharsets.UTF_8));
            headers.add(AgentProtocol.ORIGIN_HEADER, AgentProtocol.RUNNER_ORIGIN.getBytes(StandardCharsets.UTF_8));
            String value = "{\"ticketId\":\"" + ticketId + "\",\"customerId\":\"C-1001\","
                    + "\"subject\":\"Question about an invoice\",\"body\":\"Can you check my invoice?\"}";
            producer.send(new ProducerRecord<>("support.tickets", 0, ticketId, value, headers))
                    .get(20, TimeUnit.SECONDS);
        }

        long deadline = System.nanoTime() + maxWait.toNanos();
        int lastStarted = -1;
        int lastCompleted = -1;
        long lastChange = System.nanoTime();
        while (System.nanoTime() < deadline) {
            int started = count(SlowScriptedModel.CALLS, ticketId);
            int completed = count(SlowScriptedModel.COMPLETED, ticketId);
            if (started != lastStarted || completed != lastCompleted) {
                lastStarted = started;
                lastCompleted = completed;
                lastChange = System.nanoTime();
            } else if (started > 0 && started == completed && System.nanoTime() - lastChange > quiet.toNanos()) {
                return new Observation(started, completed, true);
            }
            Thread.sleep(200);
        }
        return new Observation(Math.max(lastStarted, 0), Math.max(lastCompleted, 0), false);
    }

    private static int count(Map<String, AtomicInteger> counts, String ticketId) {
        AtomicInteger value = counts.get(ticketId);
        return value == null ? 0 : value.get();
    }
}
