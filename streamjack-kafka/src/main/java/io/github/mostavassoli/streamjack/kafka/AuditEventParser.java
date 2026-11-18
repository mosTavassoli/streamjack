package io.github.mostavassoli.streamjack.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mostavassoli.streamjack.core.evidence.ToolCall;
import io.github.mostavassoli.streamjack.core.protocol.AgentProtocol;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

final class AuditEventParser {

    private static final Set<String> TOOL_CALL_FIELDS = Set.of("kind", "tool", "arguments", "sourceEventId");
    private static final Set<String> COMPLETION_FIELDS =
            Set.of("kind", "sourceEventId", "status", "errorCode");
    private static final Pattern TOOL_NAME = Pattern.compile(AgentProtocol.TOOL_NAME_REGEX);
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final ObjectMapper json;

    AuditEventParser(ObjectMapper json) {
        JsonFactory strictJsonFactory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.json = json.copyWith(strictJsonFactory);
    }

    AuditEvent parse(String value) {
        JsonNode root;
        try {
            root = json.readTree(value);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ProtocolException("audit record is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new ProtocolException("audit record must be a JSON object");
        }
        String kind = requiredText(root, "kind");
        return switch (kind) {
            case AgentProtocol.TOOL_CALL_KIND -> parseToolCall(root);
            case AgentProtocol.EVENT_COMPLETED_KIND -> parseCompletion(root);
            default -> throw new ProtocolException("unknown audit record kind " + kind);
        };
    }

    private ToolCallEvent parseToolCall(JsonNode root) {
        rejectUnknownFields(root, TOOL_CALL_FIELDS);
        String tool = requiredText(root, "tool");
        if (!TOOL_NAME.matcher(tool).matches()) {
            throw new ProtocolException("tool-call tool has an invalid name");
        }
        String sourceEventId = requiredText(root, "sourceEventId");
        JsonNode arguments = root.get("arguments");
        if (arguments == null || !arguments.isObject()) {
            throw new ProtocolException("tool-call arguments must be a JSON object");
        }
        return new ToolCallEvent(new ToolCall(tool, arguments, sourceEventId));
    }

    private CompletionEvent parseCompletion(JsonNode root) {
        rejectUnknownFields(root, COMPLETION_FIELDS);
        String sourceEventId = requiredText(root, "sourceEventId");
        String status = requiredText(root, "status");
        if (!AgentProtocol.PROCESSED_STATUS.equals(status) && !AgentProtocol.FAILED_STATUS.equals(status)) {
            throw new ProtocolException("event-completed status must be processed or failed");
        }
        JsonNode errorCodeNode = root.get("errorCode");
        String errorCode = errorCodeNode == null || errorCodeNode.isNull() ? null : requiredText(root, "errorCode");
        if (AgentProtocol.FAILED_STATUS.equals(status)) {
            if (errorCode == null || !ERROR_CODE.matcher(errorCode).matches()) {
                throw new ProtocolException("failed event-completed records require a safe errorCode");
            }
        } else if (errorCode != null) {
            throw new ProtocolException("processed event-completed records must not have an errorCode");
        }
        return new CompletionEvent(sourceEventId, status, errorCode);
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new ProtocolException(field + " must be a non-blank string");
        }
        return value.textValue();
    }

    private static void rejectUnknownFields(JsonNode root, Set<String> allowed) {
        Set<String> unknown = new HashSet<>();
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                unknown.add(field);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ProtocolException("audit record contains unknown fields " + unknown);
        }
    }

    sealed interface AuditEvent permits ToolCallEvent, CompletionEvent {
    }

    record ToolCallEvent(ToolCall toolCall) implements AuditEvent {
    }

    record CompletionEvent(String sourceEventId, String status, String errorCode) implements AuditEvent {
    }

    static final class ProtocolException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ProtocolException(String message) {
            super(message);
        }
    }
}
