package io.github.mostavassoli.streamjack.sample.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mostavassoli.streamjack.sample.KafkaTestResource;
import io.github.mostavassoli.streamjack.sample.messaging.HeaderPolicy;
import io.github.mostavassoli.streamjack.sample.model.ModelInput;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The fix for A1 and A2: the customer identity comes from the gateway's header ({@code CHANNEL}), with cross-ticket
 * memory still enabled ({@code NEUTRAL}). The header is only trustworthy when Kafka ACLs let no one but the gateway
 * write to the ticket topic; that part is tested separately with an ACL-enforcing broker. A rejected ticket is shown
 * by a later genuine ticket on the same partition reaching the model while the rejected one never does.
 */
@QuarkusTest
@TestProfile(IdentityTestSupport.ChannelIdentity.class)
@WithTestResource(KafkaTestResource.class)
class ChannelIdentityBindingTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Test
    void aTicketClaimingAnotherCustomerIsRejectedAndLeavesTheirMemoryAlone() throws Exception {
        String marker = "SJ-MARKER-" + UUID.randomUUID();
        String spoofedId = IdentityTestSupport.ticketId();
        String genuineId = IdentityTestSupport.ticketId();
        // The gateway authenticated C-1001, but the payload claims C-1002.
        IdentityTestSupport.send(bootstrapServers, spoofedId, "C-1002", "Please note " + marker, "C-1001");
        IdentityTestSupport.send(bootstrapServers, genuineId, "C-1002", "What is my account status?", "C-1002");
        ModelInput genuine = IdentityTestSupport.input(genuineId, WAIT);

        assertThat(genuine).isNotNull();
        assertThat(RecordingScriptedModel.INPUTS).as("the spoofed ticket never reached the model")
                .doesNotContainKey(spoofedId);
        assertThat(genuine.customerContext().internalApiKey()).isEqualTo("SJ-CANARY-1002-c41e8a0d93b7f256");
        assertThat(genuine.priorUntrustedTicketText()).doesNotContain(marker);
    }

    @Test
    void aTicketWithAMissingRepeatedOrMalformedGatewayIdentityIsRejected() throws Exception {
        String missing = IdentityTestSupport.ticketId();
        String repeated = IdentityTestSupport.ticketId();
        String malformed = IdentityTestSupport.ticketId();
        String barrier = IdentityTestSupport.ticketId();
        IdentityTestSupport.send(bootstrapServers, missing, "C-1003", "What is my account status?", List.of());
        IdentityTestSupport.send(bootstrapServers, repeated, "C-1003", "What is my account status?",
                List.of("C-1003", "C-1003"));
        IdentityTestSupport.send(bootstrapServers, malformed, "C-1003", "What is my account status?",
                List.of("C-1003; C-1002"));
        IdentityTestSupport.send(bootstrapServers, barrier, "C-1003", "What is my account status?", "C-1003");

        assertThat(IdentityTestSupport.input(barrier, WAIT)).isNotNull();
        assertThat(RecordingScriptedModel.INPUTS).doesNotContainKeys(missing, repeated, malformed);
    }

    @Test
    void genuineTicketsStillUseMemoryAndTheIdentityHeaderStaysOutOfTheModelInput() throws Exception {
        String marker = "SJ-MARKER-" + UUID.randomUUID();
        String firstId = IdentityTestSupport.ticketId();
        String secondId = IdentityTestSupport.ticketId();
        IdentityTestSupport.send(bootstrapServers, firstId, "C-1004", "Please note " + marker, "C-1004");
        IdentityTestSupport.send(bootstrapServers, secondId, "C-1004", "Where is my invoice?", "C-1004");
        ModelInput second = IdentityTestSupport.input(secondId, WAIT);

        assertThat(second).isNotNull();
        // Memory is on in this test, so the rejections above are due to identity binding, not to memory being off.
        assertThat(second.priorUntrustedTicketText()).contains(marker);
        assertThat(second.untrustedHeaders()).doesNotContainKey(HeaderPolicy.AUTHENTICATED_CUSTOMER_HEADER);
    }
}
