package io.github.mostavassoli.streamjack.core.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

public record ToolCall(String tool, JsonNode arguments, String sourceEventId) {

    public ToolCall {
        arguments = arguments == null ? JsonNodeFactory.instance.objectNode() : arguments;
    }
}
