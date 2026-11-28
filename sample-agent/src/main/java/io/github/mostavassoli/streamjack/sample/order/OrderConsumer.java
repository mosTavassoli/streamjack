package io.github.mostavassoli.streamjack.sample.order;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.mostavassoli.streamjack.sample.domain.OrderRecord;
import io.github.mostavassoli.streamjack.sample.messaging.Correlation;
import io.github.mostavassoli.streamjack.sample.messaging.HeaderPolicy;
import io.github.mostavassoli.streamjack.sample.messaging.KafkaOutputPublisher;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.CompletionStage;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;

/** Feeds the simulated order system from the order topic. Order records are parsed as strictly as tickets. */
@ApplicationScoped
public class OrderConsumer {

    private final ObjectReader orderReader;
    private final HeaderPolicy headerPolicy;
    private final OrderStore orders;
    private final KafkaOutputPublisher publisher;

    public OrderConsumer(ObjectMapper json, HeaderPolicy headerPolicy, OrderStore orders,
            KafkaOutputPublisher publisher) {
        this.orderReader = json.copyWith(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readerFor(OrderRecord.class);
        this.headerPolicy = headerPolicy;
        this.orders = orders;
        this.publisher = publisher;
    }

    @Incoming("orders")
    @Blocking
    public CompletionStage<Void> consume(Message<String> message) {
        IncomingKafkaRecordMetadata<?, ?> metadata = message.getMetadata(IncomingKafkaRecordMetadata.class).orElseThrow();
        Correlation correlation;
        try {
            correlation = headerPolicy.correlation(metadata.getHeaders());
        } catch (HeaderPolicy.InvalidHeaderException e) {
            return message.nack(e);
        }
        try {
            OrderRecord order = orderReader.readValue(message.getPayload());
            if (!order.orderId().equals(metadata.getKey())) {
                throw new IllegalArgumentException("Order id does not match the record key");
            }
            orders.put(order);
            publisher.completed(correlation);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            publisher.failed(correlation, "INVALID_EVENT");
        } catch (RuntimeException e) {
            publisher.failed(correlation, "PROCESSING_FAILED");
        }
        return message.ack();
    }
}
