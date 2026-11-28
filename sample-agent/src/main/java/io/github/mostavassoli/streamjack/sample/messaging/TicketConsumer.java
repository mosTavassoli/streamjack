package io.github.mostavassoli.streamjack.sample.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.mostavassoli.streamjack.sample.action.SupportActions;
import io.github.mostavassoli.streamjack.sample.config.AgentConfiguration;
import io.github.mostavassoli.streamjack.sample.customer.CustomerContext;
import io.github.mostavassoli.streamjack.sample.customer.CustomerDirectory;
import io.github.mostavassoli.streamjack.sample.domain.Disposition;
import io.github.mostavassoli.streamjack.sample.domain.OrderRecord;
import io.github.mostavassoli.streamjack.sample.order.OrderStore;
import io.github.mostavassoli.streamjack.sample.domain.TicketAnalysis;
import io.github.mostavassoli.streamjack.sample.domain.TicketCreated;
import io.github.mostavassoli.streamjack.sample.domain.TriageResult;
import io.github.mostavassoli.streamjack.sample.memory.TicketMemory;
import io.github.mostavassoli.streamjack.sample.model.AnalysisValidator;
import io.github.mostavassoli.streamjack.sample.model.ModelGateway;
import io.github.mostavassoli.streamjack.sample.model.ModelInput;
import io.github.mostavassoli.streamjack.sample.policy.AgentPolicy;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;

@ApplicationScoped
public class TicketConsumer {

    private final ObjectReader ticketReader;
    private final HeaderPolicy headerPolicy;
    private final CustomerDirectory customers;
    private final SupportActions actions;
    private final ModelGateway model;
    private final AnalysisValidator analysisValidator;
    private final AgentPolicy policy;
    private final TicketMemory memory;
    private static final java.time.Duration ORDER_WAIT = java.time.Duration.ofSeconds(10);

    private final KafkaOutputPublisher publisher;
    private final AgentConfiguration configuration;
    private final OrderStore orders;

    public TicketConsumer(
            AgentConfiguration configuration,
            OrderStore orders,
            ObjectMapper json,
            HeaderPolicy headerPolicy,
            CustomerDirectory customers,
            SupportActions actions,
            ModelGateway model,
            AnalysisValidator analysisValidator,
            AgentPolicy policy,
            TicketMemory memory,
            KafkaOutputPublisher publisher) {
        JsonFactory strictJsonFactory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.ticketReader = json.copyWith(strictJsonFactory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readerFor(TicketCreated.class);
        this.headerPolicy = headerPolicy;
        this.customers = customers;
        this.actions = actions;
        this.model = model;
        this.analysisValidator = analysisValidator;
        this.policy = policy;
        this.memory = memory;
        this.publisher = publisher;
        this.configuration = configuration;
        this.orders = orders;
    }

    @Incoming("tickets")
    @Blocking
    public CompletionStage<Void> consume(Message<String> message) {
        IncomingKafkaRecordMetadata<String, String> metadata = message
                .getMetadata(IncomingKafkaRecordMetadata.class)
                .map(TicketConsumer::<String, String>castMetadata)
                .orElseThrow();
        Correlation correlation;
        try {
            correlation = headerPolicy.correlation(metadata.getHeaders());
        } catch (HeaderPolicy.InvalidHeaderException e) {
            return message.nack(e);
        }

        try {
            TicketCreated ticket = readTicket(message.getPayload());
            if (!ticket.ticketId().equals(metadata.getKey())) {
                throw new InvalidTicketException();
            }
            process(ticket, customerId(ticket, metadata), metadata, correlation);
            publisher.completed(correlation);
            return message.ack();
        } catch (InvalidTicketException | JsonProcessingException e) {
            publisher.failed(correlation, "INVALID_EVENT");
            return message.ack();
        } catch (OrderUnavailableException e) {
            publisher.failed(correlation, "ORDER_NOT_AVAILABLE");
            return message.ack();
        } catch (AnalysisValidator.InvalidModelOutputException e) {
            publisher.failed(correlation, "MODEL_OUTPUT_INVALID");
            return message.ack();
        } catch (RuntimeException e) {
            publisher.failed(correlation, "PROCESSING_FAILED");
            return message.ack();
        }
    }

    /**
     * The customer this ticket is processed for. With {@code CHANNEL}, the gateway's authenticated identity must be
     * present and match the payload's claim; with {@code PAYLOAD}, the claim is trusted as is.
     */
    private String customerId(TicketCreated ticket, IncomingKafkaRecordMetadata<String, String> metadata) {
        if (configuration.identitySource() == AgentConfiguration.IdentitySource.PAYLOAD) {
            return ticket.customerId();
        }
        String authenticated;
        try {
            authenticated = headerPolicy.authenticatedCustomer(metadata.getHeaders());
        } catch (HeaderPolicy.InvalidHeaderException e) {
            throw new InvalidTicketException();
        }
        if (!authenticated.equals(ticket.customerId())) {
            throw new InvalidTicketException();
        }
        return authenticated;
    }

    private void process(
            TicketCreated ticket,
            String customerId,
            IncomingKafkaRecordMetadata<String, String> metadata,
            Correlation correlation) {
        actions.lookupCustomer(correlation, customerId);
        CustomerContext customerContext = customers.findPromptContext(customerId);
        Map<String, String> headers = headerPolicy.modelHeaders(metadata.getHeaders());
        String priorText = policy.retainCrossTicketMemory() ? memory.previous(customerId) : "";
        OrderRecord order = orderFor(ticket, customerId);
        TicketAnalysis analysis = analysisValidator.validate(
                model.analyze(new ModelInput(ticket, customerContext, headers, priorText, order)));
        Disposition disposition = policy.disposition(analysis);

        if (analysis.followUpRequested()) {
            actions.createFollowUp(correlation, ticket.ticketId());
        }
        if (disposition == Disposition.PUBLISHED) {
            try {
                actions.publishReply(correlation, ticket.ticketId(), analysis.draft());
            } catch (RuntimeException e) {
                // Only an authorization denial proves the reply was not published: then route the ticket to a
                // human. Other failures (for example, a lost acknowledgement) leave the outcome unknown, so no
                // triage record is written. Either way the event fails.
                if (isDenied(e)) {
                    publisher.triage(correlation, TriageResult.from(ticket, analysis, Disposition.HUMAN_REVIEW));
                }
                throw e;
            }
        }
        // Written after any publication so the recorded disposition reflects what actually happened.
        publisher.triage(correlation, TriageResult.from(ticket, analysis, disposition));
        if (policy.retainCrossTicketMemory()) {
            memory.remember(customerId, ticket.body());
        }
    }

    /**
     * The order the ticket refers to (the first order id in its subject or body), as the order system holds it, or
     * {@code null} if it refers to none or the order belongs to another customer. Waits briefly for the order record,
     * because orders and tickets are read from different topics.
     *
     * @throws OrderUnavailableException if the ticket refers to an order the order system does not provide in time
     */
    private OrderRecord orderFor(TicketCreated ticket, String customerId) {
        if (!configuration.orderLookup()) {
            return null;
        }
        // Only the ticket is searched; order notes are never parsed for ids.
        java.util.regex.Matcher id = OrderRecord.ORDER_ID_IN_TEXT.matcher(ticket.subject() + "\n" + ticket.body());
        if (!id.find()) {
            return null;
        }
        OrderRecord order;
        try {
            order = orders.await(id.group(), ORDER_WAIT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OrderUnavailableException();
        }
        if (order == null) {
            throw new OrderUnavailableException();
        }
        if (!order.belongsTo(customerId)) {
            return null;
        }
        return policy.withholdOrderNotes() ? order.withoutNote() : order;
    }

    private static boolean isDenied(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.apache.kafka.common.errors.AuthorizationException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private TicketCreated readTicket(String payload) throws JsonProcessingException {
        try {
            return ticketReader.readValue(payload);
        } catch (IllegalArgumentException e) {
            throw new InvalidTicketException();
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> IncomingKafkaRecordMetadata<K, V> castMetadata(IncomingKafkaRecordMetadata<?, ?> metadata) {
        return (IncomingKafkaRecordMetadata<K, V>) metadata;
    }

    private static final class OrderUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    private static final class InvalidTicketException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
