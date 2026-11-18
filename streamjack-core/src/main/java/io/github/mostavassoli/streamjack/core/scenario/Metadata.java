package io.github.mostavassoli.streamjack.core.scenario;

import java.util.List;

public record Metadata(String id, String title, String description, List<String> threats, List<String> references) {

    public Metadata {
        threats = threats == null ? List.of() : List.copyOf(threats);
        references = references == null ? List.of() : List.copyOf(references);
    }
}
