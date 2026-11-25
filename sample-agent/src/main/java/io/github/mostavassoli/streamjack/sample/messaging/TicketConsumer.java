package io.github.mostavassoli.streamjack.sample.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.mostavassoli.streamjack.sample.action.SupportActions;
import io.github.mostavassoli.streamjack.sample.customer.CustomerContext;
import io.github.mostavassoli.streamjack.sample.customer.CustomerDirectory;
import io.github.mostavassoli.streamjack.sample.domain.Disposition;
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
    private final KafkaOutputPublisher publisher;

    public TicketConsumer(
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
            process(ticket, metadata, correlation);
            publisher.completed(correlation);
            return message.ack();
        } catch (InvalidTicketException | JsonProcessingException e) {
            publisher.failed(correlation, "INVALID_EVENT");
            return message.ack();
        } catch (AnalysisValidator.InvalidModelOutputException e) {
            publisher.failed(correlation, "MODEL_OUTPUT_INVALID");
            return message.ack();
        } catch (RuntimeException e) {
            publisher.failed(correlation, "PROCESSING_FAILED");
            return message.ack();
        }
    }

    private void process(
            TicketCreated ticket,
            IncomingKafkaRecordMetadata<String, String> metadata,
            Correlation correlation) {
        actions.lookupCustomer(correlation, ticket.customerId());
        CustomerContext customerContext = customers.findPromptContext(ticket.customerId());
        Map<String, String> headers = headerPolicy.modelHeaders(metadata.getHeaders());
        String priorText = policy.retainCrossTicketMemory() ? memory.previous(ticket.customerId()) : "";
        TicketAnalysis analysis = analysisValidator.validate(
                model.analyze(new ModelInput(ticket, customerContext, headers, priorText)));
        Disposition disposition = policy.disposition(analysis);

        if (analysis.followUpRequested()) {
            actions.createFollowUp(correlation, ticket.ticketId());
        }
        if (disposition == Disposition.PUBLISHED) {
            try {
                actions.publishReply(correlation, ticket.ticketId(), analysis.draft());
            } catch (RuntimeException e) {
                // The reply was not published (for example, Kafka denied the write), so the triage record must
                // not claim it was: route the ticket to a human, then fail the event.
                publisher.triage(correlation, TriageResult.from(ticket, analysis, Disposition.HUMAN_REVIEW));
                throw e;
            }
        }
        // Written after any publication so the recorded disposition reflects what actually happened.
        publisher.triage(correlation, TriageResult.from(ticket, analysis, disposition));
        if (policy.retainCrossTicketMemory()) {
            memory.remember(ticket.customerId(), ticket.body());
        }
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

    private static final class InvalidTicketException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
