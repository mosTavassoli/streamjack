package io.github.mostavassoli.streamjack.sample.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mostavassoli.streamjack.core.protocol.AgentProtocol;
import io.github.mostavassoli.streamjack.sample.domain.PublishedReply;
import io.github.mostavassoli.streamjack.sample.domain.TriageResult;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;

@ApplicationScoped
public class KafkaOutputPublisher {

    private static final String AGENT_ORIGIN = "sample-agent";

    private final ObjectMapper json;

    @Inject
    @Channel("triage-results")
    MutinyEmitter<String> triageEmitter;

    @Inject
    @Channel("audit-events")
    MutinyEmitter<String> auditEmitter;

    @Inject
    @Channel("published-replies")
    MutinyEmitter<String> publishedEmitter;

    public KafkaOutputPublisher(ObjectMapper json) {
        this.json = json;
    }

    public void triage(Correlation correlation, TriageResult result) {
        send(triageEmitter, result.ticketId(), toJson(result), correlation);
    }

    public void published(Correlation correlation, PublishedReply reply) {
        send(publishedEmitter, reply.ticketId(), toJson(reply), correlation);
    }

    public void toolCall(Correlation correlation, String tool, Map<String, String> arguments) {
        ObjectNode root = json.createObjectNode();
        root.put("kind", AgentProtocol.TOOL_CALL_KIND);
        root.put("tool", tool);
        ObjectNode argumentNode = root.putObject("arguments");
        arguments.forEach(argumentNode::put);
        root.put("sourceEventId", correlation.eventId());
        send(auditEmitter, correlation.eventId(), root.toString(), correlation);
    }

    public void completed(Correlation correlation) {
        ObjectNode root = json.createObjectNode();
        root.put("kind", AgentProtocol.EVENT_COMPLETED_KIND);
        root.put("sourceEventId", correlation.eventId());
        root.put("status", AgentProtocol.PROCESSED_STATUS);
        send(auditEmitter, correlation.eventId(), root.toString(), correlation);
    }

    public void failed(Correlation correlation, String errorCode) {
        ObjectNode root = json.createObjectNode();
        root.put("kind", AgentProtocol.EVENT_COMPLETED_KIND);
        root.put("sourceEventId", correlation.eventId());
        root.put("status", AgentProtocol.FAILED_STATUS);
        root.put("errorCode", errorCode);
        send(auditEmitter, correlation.eventId(), root.toString(), correlation);
    }

    private void send(MutinyEmitter<String> emitter, String key, String value, Correlation correlation) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(AgentProtocol.RUN_ID_HEADER, bytes(correlation.runId()));
        headers.add(AgentProtocol.EVENT_ID_HEADER, bytes(correlation.eventId()));
        headers.add(AgentProtocol.ORIGIN_HEADER, bytes(AGENT_ORIGIN));
        OutgoingKafkaRecordMetadata<String> metadata = OutgoingKafkaRecordMetadata.<String>builder()
                .withKey(key)
                .withHeaders(headers)
                .build();
        emitter.sendMessageAndAwait(Message.of(value).addMetadata(metadata));
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize agent output", e);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
