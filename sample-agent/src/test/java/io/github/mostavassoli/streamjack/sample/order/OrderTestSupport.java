package io.github.mostavassoli.streamjack.sample.order;

import io.github.mostavassoli.streamjack.core.protocol.AgentProtocol;
import io.github.mostavassoli.streamjack.sample.identity.RecordingScriptedModel;
import io.github.mostavassoli.streamjack.sample.model.ModelInput;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringSerializer;

/** Sends synthetic order records and tickets, and reads what the scripted model received and answered. */
final class OrderTestSupport {

    private OrderTestSupport() {
    }

    abstract static class Profile implements QuarkusTestProfile {

        abstract String securityMode();

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "streamjack.agent.model", "SCRIPTED",
                    "streamjack.agent.security-mode", securityMode(),
                    "streamjack.agent.order-lookup", "true");
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(RecordingScriptedModel.class);
        }
    }

    public static final class Neutral extends Profile {

        @Override
        String securityMode() {
            return "NEUTRAL";
        }
    }

    public static final class Hardened extends Profile {

        @Override
        String securityMode() {
            return "HARDENED";
        }
    }

    static String id(String prefix) {
        return prefix + (100_000_000_000L + ThreadLocalRandom.current().nextLong(899_999_999_999L));
    }

    static void order(String bootstrapServers, String orderId, String customerId, String status, String note)
            throws Exception {
        send(bootstrapServers, "support.orders", orderId, "{\"orderId\":\"" + orderId + "\",\"customerId\":\""
                + customerId + "\",\"status\":\"" + status + "\",\"note\":\"" + note + "\"}");
    }

    static void ticket(String bootstrapServers, String ticketId, String customerId, String body) throws Exception {
        send(bootstrapServers, "support.tickets", ticketId, "{\"ticketId\":\"" + ticketId + "\",\"customerId\":\""
                + customerId + "\",\"subject\":\"Order question\",\"body\":\"" + body + "\"}");
    }

    private static void send(String bootstrapServers, String topic, String key, String value) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", bootstrapServers,
                "key.serializer", StringSerializer.class.getName(),
                "value.serializer", StringSerializer.class.getName()))) {
            RecordHeaders headers = new RecordHeaders();
            headers.add(AgentProtocol.RUN_ID_HEADER, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            headers.add(AgentProtocol.EVENT_ID_HEADER, key.getBytes(StandardCharsets.UTF_8));
            headers.add(AgentProtocol.ORIGIN_HEADER, AgentProtocol.RUNNER_ORIGIN.getBytes(StandardCharsets.UTF_8));
            producer.send(new ProducerRecord<>(topic, 0, key, value, headers)).get(20, TimeUnit.SECONDS);
        }
    }

    static ModelInput input(String ticketId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            ModelInput input = RecordingScriptedModel.INPUTS.get(ticketId);
            if (input != null) {
                return input;
            }
            Thread.sleep(100);
        }
        return null;
    }
}
