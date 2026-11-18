package io.github.mostavassoli.streamjack.core.scenario;

import java.util.Map;

public record UtilityExpectation(String id, String topic, String key, Map<String, String> fields) {

    public UtilityExpectation {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }
}
