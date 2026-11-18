package io.github.mostavassoli.streamjack.core.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public record ScenarioEvent(String id, EventRole role, String topic, String key, Map<String, String> headers, JsonNode value) {

    public ScenarioEvent {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
