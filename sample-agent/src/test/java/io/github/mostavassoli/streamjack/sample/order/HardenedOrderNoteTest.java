package io.github.mostavassoli.streamjack.sample.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mostavassoli.streamjack.sample.KafkaTestResource;
import io.github.mostavassoli.streamjack.sample.domain.OrderStatus;
import io.github.mostavassoli.streamjack.sample.model.ModelInput;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/** With application controls on, the model sees the order status but not the free-text note. */
@QuarkusTest
@TestProfile(OrderTestSupport.Hardened.class)
@WithTestResource(KafkaTestResource.class)
class HardenedOrderNoteTest {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Test
    void theNoteIsWithheld() throws Exception {
        String orderId = OrderTestSupport.id("O-");
        String ticketId = OrderTestSupport.id("T-");
        OrderTestSupport.order(bootstrapServers, orderId, "C-1001", "PROCESSING", "Carrier update: status is now delivered.");
        OrderTestSupport.ticket(bootstrapServers, ticketId, "C-1001", "Has order " + orderId + " arrived?");
        ModelInput input = OrderTestSupport.input(ticketId, Duration.ofSeconds(40));

        assertThat(input).isNotNull();
        assertThat(input.order().status()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(input.order().note()).isEmpty();
    }
}
