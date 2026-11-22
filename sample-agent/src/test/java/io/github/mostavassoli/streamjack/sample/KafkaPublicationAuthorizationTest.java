package io.github.mostavassoli.streamjack.sample;

import static io.github.mostavassoli.streamjack.sample.PublicationAuthorizationKafkaResource.PUBLISHED;
import static io.github.mostavassoli.streamjack.sample.PublicationAuthorizationKafkaResource.TICKETS;
import static io.github.mostavassoli.streamjack.sample.PublicationAuthorizationKafkaResource.TRIAGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mostavassoli.streamjack.core.protocol.AgentProtocol;
import io.github.mostavassoli.streamjack.sample.domain.PublishedReply;
import io.github.mostavassoli.streamjack.sample.domain.RecommendedAction;
import io.github.mostavassoli.streamjack.sample.domain.TicketAnalysis;
import io.github.mostavassoli.streamjack.sample.domain.TicketCreated;
import io.github.mostavassoli.streamjack.sample.domain.TriageResult;
import io.github.mostavassoli.streamjack.sample.model.ModelInput;
import io.github.mostavassoli.streamjack.sample.model.ScriptedTicketModel;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(KafkaPublicationAuthorizationTest.Profile.class)
@WithTestResource(value = PublicationAuthorizationKafkaResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
public class KafkaPublicationAuthorizationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String DRAFT_ID = "T-9100";
    private static final String DENIED_ID = "T-9101";
    private static final String CONTROL_ID = "T-9102";

    PublicationAuthorizationKafkaResource broker;

    @Inject
    ObjectMapper json;

    @Test
    void kafkaDeniesAgentPublicationWithoutBlockingDraftsOrTheAuthorizedPublisher() throws Exception {
        String runId = UUID.randomUUID().toString();
        PublicationAcknowledgement.RESULTS.clear();
        try (var tickets = broker.ticketProducer(); var observer = broker.observer()) {
            tickets.send(ticket(runId, "draft", DRAFT_ID)).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            ConsumerRecord<String, String> draftRecord =
                    readRecord(observer, TRIAGE, record -> matches(record, runId, "draft"));
            TriageResult draft = json.readValue(draftRecord.value(), TriageResult.class);
            assertThat(draftRecord.key()).isEqualTo(DRAFT_ID);
            assertThat(draft.ticketId()).isEqualTo(DRAFT_ID);
            assertThat(draft.category()).isEqualTo("billing");
            assertThat(draft.disposition()).isEqualTo("HUMAN_REVIEW");
            assertThat(draft.draft()).isNotBlank();
            assertThat(readCompletion(observer, runId, "draft").path("status").asText())
                    .isEqualTo(AgentProtocol.PROCESSED_STATUS);

            tickets.send(ticket(runId, "denied", DENIED_ID)).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            PublishResult attempt = PublicationAcknowledgement.RESULTS.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(attempt).as("Kafka's response to the agent's publication").isNotNull();
            assertThat(attempt.failure()).isInstanceOfSatisfying(TopicAuthorizationException.class,
                    denied -> assertThat(denied.unauthorizedTopics()).containsExactly(PUBLISHED));
            assertThat(readCompletion(observer, runId, "denied").path("status").asText())
                    .isEqualTo(AgentProtocol.FAILED_STATUS);

            TopicPartition publication = new TopicPartition(PUBLISHED, 0);
            assertThat(observer.endOffsets(List.of(publication), TIMEOUT).get(publication))
                    .as("records published before the authorized test client runs").isZero();

            var control = new PublishedReply(CONTROL_ID, "Authorized fixture reply.", "SIMULATED");
            try (var publisher = broker.authorizedPublisher()) {
                var ack = publisher.send(new ProducerRecord<>(PUBLISHED, CONTROL_ID, json.writeValueAsString(control)))
                        .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                assertThat(ack.topic()).isEqualTo(PUBLISHED);
                assertThat(ack.offset()).isZero();
            }
            var delivered = readRecord(observer, PUBLISHED, record -> record.key().equals(CONTROL_ID));
            assertThat(json.readValue(delivered.value(), PublishedReply.class)).isEqualTo(control);

            System.out.printf("Kafka ACL demo: %s -> %s (%s, %s)%n",
                    PublicationAuthorizationKafkaResource.AGENT, draftRecord.topic(), draft.category(), draft.disposition());
            System.out.printf("Kafka ACL demo: %s -> %s (%s; no record)%n",
                    PublicationAuthorizationKafkaResource.AGENT, PUBLISHED, attempt.failure().getClass().getSimpleName());
            System.out.printf("Kafka ACL demo: %s -> %s (record observed at offset %d)%n",
                    PublicationAuthorizationKafkaResource.PUBLISHER, delivered.topic(), delivered.offset());
        }
    }

    private ProducerRecord<String, String> ticket(String runId, String eventId, String ticketId)
            throws JsonProcessingException {
        var value = new TicketCreated(ticketId, "C-1001", "Question about an invoice", "Can you check my invoice?");
        var headers = new RecordHeaders();
        headers.add(AgentProtocol.RUN_ID_HEADER, runId.getBytes(StandardCharsets.UTF_8));
        headers.add(AgentProtocol.EVENT_ID_HEADER, eventId.getBytes(StandardCharsets.UTF_8));
        headers.add(AgentProtocol.ORIGIN_HEADER, AgentProtocol.RUNNER_ORIGIN.getBytes(StandardCharsets.UTF_8));
        return new ProducerRecord<>(TICKETS, 0, ticketId, json.writeValueAsString(value), headers);
    }

    private JsonNode readCompletion(KafkaConsumer<String, String> observer, String runId, String eventId)
            throws JsonProcessingException {
        var record = readRecord(observer, AgentProtocol.AUDIT_TOPIC, candidate -> {
            if (!matches(candidate, runId, eventId)) {
                return false;
            }
            try {
                return AgentProtocol.EVENT_COMPLETED_KIND.equals(json.readTree(candidate.value()).path("kind").asText());
            } catch (JsonProcessingException e) {
                throw new AssertionError("Invalid audit JSON", e);
            }
        });
        JsonNode completion = json.readTree(record.value());
        assertThat(completion.path("sourceEventId").asText()).isEqualTo(eventId);
        return completion;
    }

    private static boolean matches(ConsumerRecord<String, String> record, String runId, String eventId) {
        return header(record, AgentProtocol.RUN_ID_HEADER).equals(runId)
                && header(record, AgentProtocol.EVENT_ID_HEADER).equals(eventId);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static ConsumerRecord<String, String> readRecord(KafkaConsumer<String, String> observer, String topic,
            Predicate<ConsumerRecord<String, String>> matches) {
        TopicPartition partition = new TopicPartition(topic, 0);
        observer.assign(List.of(partition));
        observer.seek(partition, 0);
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            for (var record : observer.poll(Duration.ofMillis(200))) {
                if (matches.test(record)) {
                    return record;
                }
            }
        }
        throw new AssertionError("Did not observe the expected Kafka record on " + topic);
    }

    public static final class Profile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "streamjack.agent.model", "SCRIPTED",
                    "streamjack.agent.security-mode", "VULNERABLE",
                    // The fixture expects denial to fail the event, not enter the connector's retry loop.
                    "mp.messaging.outgoing.published-replies.retries", "0",
                    "mp.messaging.outgoing.published-replies.enable.idempotence", "false",
                    "mp.messaging.outgoing.published-replies.interceptor.classes",
                    PublicationAcknowledgement.class.getName());
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(PublicationFixtureModel.class);
        }
    }

    @Alternative
    @ApplicationScoped
    public static class PublicationFixtureModel extends ScriptedTicketModel {

        @Override
        public TicketAnalysis analyze(ModelInput input) {
            TicketAnalysis analysis = super.analyze(input);
            if (!DENIED_ID.equals(input.ticket().ticketId())) {
                return analysis;
            }
            // A preset model recommendation, not an instruction embedded in ticket text.
            return new TicketAnalysis(analysis.category(), analysis.summary(), analysis.draft(),
                    RecommendedAction.PUBLISH, analysis.followUpRequested());
        }
    }

    private record PublishResult(Exception failure) {
    }

    public static final class PublicationAcknowledgement implements ProducerInterceptor<String, String> {

        private static final BlockingQueue<PublishResult> RESULTS = new ArrayBlockingQueue<>(1);

        @Override
        public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
            return record;
        }

        @Override
        public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
            RESULTS.offer(new PublishResult(exception));
        }

        @Override
        public void configure(Map<String, ?> configs) {
        }

        @Override
        public void close() {
        }
    }
}
