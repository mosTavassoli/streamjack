package io.github.mostavassoli.streamjack.core.evidence;

import java.util.Map;

public record ObservedRecord(String topic, String key, Map<String, String> headers, String value) {

    public ObservedRecord {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
