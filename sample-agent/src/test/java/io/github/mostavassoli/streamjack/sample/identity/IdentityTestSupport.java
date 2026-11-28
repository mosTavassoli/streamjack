package io.github.mostavassoli.streamjack.sample.identity;

import io.github.mostavassoli.streamjack.core.protocol.AgentProtocol;
import io.github.mostavassoli.streamjack.sample.messaging.HeaderPolicy;
import io.github.mostavassoli.streamjack.sample.model.ModelInput;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Sends synthetic tickets straight to the ticket topic and reads what the model received. Every ticket goes to
 * partition 0, so the agent handles them in order: once a later ticket reaches the model, every earlier one has been
 * fully processed (analyzed or rejected, and remembered).
 */
final class IdentityTestSupport {

    private IdentityTestSupport() {
    }

    /** {@code NEUTRAL}: no application controls, so cross-ticket memory is on and the synthetic key is in context. */
    abstract static class Profile implements QuarkusTestProfile {

        abstract String identitySource();

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "streamjack.agent.model", "SCRIPTED",
                    "streamjack.agent.security-mode", "NEUTRAL",
                    "streamjack.agent.identity-source", identitySource());
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(RecordingScriptedModel.class);
        }
    }

    public static final class PayloadIdentity extends Profile {

        @Override
        String identitySource() {
            return "PAYLOAD";
        }
    }

    public static final class ChannelIdentity extends Profile {

        @Override
        String identitySource() {
            return "CHANNEL";
        }
    }

    /** A ticket id not used before in this JVM, so recorded inputs from earlier runs cannot satisfy an assertion. */
    static String ticketId() {
        return "T-" + (100_000_000_000L + java.util.concurrent.ThreadLocalRandom.current().nextLong(899_999_999_999L));
    }

    static void send(String bootstrapServers, String ticketId, String claimedCustomer, String body,
            String authenticatedCustomer) throws Exception {
        send(bootstrapServers, ticketId, claimedCustomer, body,
                authenticatedCustomer == null ? java.util.List.of() : java.util.List.of(authenticatedCustomer));
    }

    /**
     * Sends one ticket.
     *
     * @param claimedCustomer the payload's {@code customerId}
     * @param authenticatedCustomers the gateway identity headers to attach, in order (none, one, or several)
     */
    static void send(String bootstrapServers, String ticketId, String claimedCustomer, String body,
            java.util.List<String> authenticatedCustomers) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", bootstrapServers,
                "key.serializer", StringSerializer.class.getName(),
                "value.serializer", StringSerializer.class.getName()))) {
            RecordHeaders headers = new RecordHeaders();
            headers.add(AgentProtocol.RUN_ID_HEADER, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            headers.add(AgentProtocol.EVENT_ID_HEADER, ticketId.getBytes(StandardCharsets.UTF_8));
            headers.add(AgentProtocol.ORIGIN_HEADER, AgentProtocol.RUNNER_ORIGIN.getBytes(StandardCharsets.UTF_8));
            for (String authenticatedCustomer : authenticatedCustomers) {
                headers.add(HeaderPolicy.AUTHENTICATED_CUSTOMER_HEADER,
                        authenticatedCustomer.getBytes(StandardCharsets.UTF_8));
            }
            String value = "{\"ticketId\":\"" + ticketId + "\",\"customerId\":\"" + claimedCustomer
                    + "\",\"subject\":\"Account question\",\"body\":\"" + body + "\"}";
            producer.send(new ProducerRecord<>("support.tickets", 0, ticketId, value, headers)).get(20, TimeUnit.SECONDS);
        }
    }

    /** What the model received for this ticket, waiting up to the timeout; {@code null} if it never got there. */
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
