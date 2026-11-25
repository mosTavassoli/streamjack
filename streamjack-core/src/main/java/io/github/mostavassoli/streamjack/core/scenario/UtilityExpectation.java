package io.github.mostavassoli.streamjack.core.scenario;

import java.util.Map;

/**
 * An expected result of useful work. Set exactly one of {@code topic} (a matching output record) or {@code tool}
 * (an audited call to the tool with matching arguments).
 */
public record UtilityExpectation(
        String id, String topic, String key, Map<String, String> fields, String tool, Map<String, String> arguments) {

    public UtilityExpectation {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
