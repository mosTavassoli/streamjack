package io.github.mostavassoli.streamjack.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mostavassoli.streamjack.core.protocol.AgentProtocol;
import io.github.mostavassoli.streamjack.sample.identity.RecordingScriptedModel;
import io.github.mostavassoli.streamjack.sample.messaging.HeaderPolicy;
import io.github.mostavassoli.streamjack.sample.model.ModelInput;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

/**
 * Why the gateway's identity header can be trusted: Kafka ACLs let only the gateway principal write to the ticket
 * topic. An untrusted customer app can reach only the intake topic, so it cannot forge the header. Runs with
 * {@code CHANNEL} identity and cross-ticket memory on ({@code NEUTRAL}), against a disposable SASL/ACL broker.
 */
@QuarkusTest
@TestProfile(GatewayIdentityAclTest.ChannelIdentityOnAclBroker.class)
@WithTestResource(value = PublicationAuthorizationKafkaResource.class,
        scope = io.quarkus.test.common.TestResourceScope.RESTRICTED_TO_CLASS)
class GatewayIdentityAclTest {

    PublicationAuthorizationKafkaResource kafka;

    @Test
    void anUntrustedAppCannotWriteTicketsSoItCannotForgeTheIdentityHeader() {
        try (KafkaProducer<String, String> app = kafka.customerAppProducer()) {
            assertThatThrownBy(() -> send(app, PublicationAuthorizationKafkaResource.TICKETS, "T-93001", "C-1002",
                    "C-1002"))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TopicAuthorizationException.class);
        }
    }

    @Test
    void theUntrustedAppCanStillSubmitThroughTheIntake() throws Exception {
        try (KafkaProducer<String, String> app = kafka.customerAppProducer()) {
            send(app, PublicationAuthorizationKafkaResource.INTAKE, "T-93011", "C-1001", null);
        }
    }

    @Test
    void aTicketFromTheGatewayIsProcessedForTheAuthenticatedCustomer() throws Exception {
        try (KafkaProducer<String, String> gateway = kafka.gatewayProducer()) {
            send(gateway, PublicationAuthorizationKafkaResource.TICKETS, "T-93021", "C-1002", "C-1002");
        }
        ModelInput input = null;
        for (int i = 0; i < 300 && input == null; i++) {
            input = RecordingScriptedModel.INPUTS.get("T-93021");
            Thread.sleep(100);
        }
        assertThat(input).as("the gateway's ticket reached the model").isNotNull();
        assertThat(input.customerContext().displayName()).isEqualTo("Sam Rivera");
    }

    private static void send(KafkaProducer<String, String> producer, String topic, String ticketId, String claimed,
            String authenticated) throws Exception {
        RecordHeaders headers = new RecordHeaders();
        headers.add(AgentProtocol.RUN_ID_HEADER, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        headers.add(AgentProtocol.EVENT_ID_HEADER, ticketId.getBytes(StandardCharsets.UTF_8));
        headers.add(AgentProtocol.ORIGIN_HEADER, AgentProtocol.RUNNER_ORIGIN.getBytes(StandardCharsets.UTF_8));
        if (authenticated != null) {
            headers.add(HeaderPolicy.AUTHENTICATED_CUSTOMER_HEADER, authenticated.getBytes(StandardCharsets.UTF_8));
        }
        String value = "{\"ticketId\":\"" + ticketId + "\",\"customerId\":\"" + claimed
                + "\",\"subject\":\"Account question\",\"body\":\"What is my account status?\"}";
        producer.send(new ProducerRecord<>(topic, 0, ticketId, value, headers)).get(20, TimeUnit.SECONDS);
    }

    public static final class ChannelIdentityOnAclBroker implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "streamjack.agent.model", "SCRIPTED",
                    "streamjack.agent.security-mode", "NEUTRAL",
                    "streamjack.agent.identity-source", "CHANNEL");
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(RecordingScriptedModel.class);
        }
    }
}
