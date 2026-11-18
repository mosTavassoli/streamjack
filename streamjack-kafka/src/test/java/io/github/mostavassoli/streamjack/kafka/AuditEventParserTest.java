package io.github.mostavassoli.streamjack.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mostavassoli.streamjack.core.protocol.AgentProtocol;
import org.junit.jupiter.api.Test;

class AuditEventParserTest {

    private final AuditEventParser parser = new AuditEventParser(new ObjectMapper());

    @Test
    void parsesToolCall() {
        String value = """
                {
                  "kind": "tool-call",
                  "tool": "createFollowUp",
                  "arguments": {"ticketId": "T-1"},
                  "sourceEventId": "e1"
                }
                """;

        AuditEventParser.ToolCallEvent event = (AuditEventParser.ToolCallEvent) parser.parse(value);

        assertThat(event.toolCall().tool()).isEqualTo("createFollowUp");
        assertThat(event.toolCall().arguments().get("ticketId").asText()).isEqualTo("T-1");
        assertThat(event.toolCall().sourceEventId()).isEqualTo("e1");
    }

    @Test
    void parsesSuccessfulCompletion() {
        String value = """
                {"kind":"event-completed","sourceEventId":"e1","status":"processed"}
                """;

        AuditEventParser.CompletionEvent event = (AuditEventParser.CompletionEvent) parser.parse(value);

        assertThat(event.status()).isEqualTo(AgentProtocol.PROCESSED_STATUS);
        assertThat(event.errorCode()).isNull();
    }

    @Test
    void requiresSafeErrorCodeForFailure() {
        String value = """
                {
                  "kind": "event-completed",
                  "sourceEventId": "e1",
                  "status": "failed",
                  "errorCode": "customer secret was SJ-CANARY-123"
                }
                """;

        assertThatThrownBy(() -> parser.parse(value))
                .isInstanceOf(AuditEventParser.ProtocolException.class)
                .hasMessageContaining("safe errorCode");
    }

    @Test
    void rejectsUnknownFields() {
        String value = """
                {
                  "kind": "tool-call",
                  "tool": "createFollowUp",
                  "arguments": {},
                  "sourceEventId": "e1",
                  "reasoning": "hidden chain of thought"
                }
                """;

        assertThatThrownBy(() -> parser.parse(value))
                .isInstanceOf(AuditEventParser.ProtocolException.class)
                .hasMessageContaining("unknown fields", "reasoning");
    }

    @Test
    void rejectsDuplicateJsonFields() {
        String value = """
                {
                  "kind": "event-completed",
                  "sourceEventId": "e1",
                  "sourceEventId": "e2",
                  "status": "processed"
                }
                """;

        assertThatThrownBy(() -> parser.parse(value))
                .isInstanceOf(AuditEventParser.ProtocolException.class)
                .hasMessageContaining("not valid JSON");
    }
}
