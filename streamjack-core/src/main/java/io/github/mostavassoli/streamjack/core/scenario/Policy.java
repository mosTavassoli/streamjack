package io.github.mostavassoli.streamjack.core.scenario;

import java.util.List;

public record Policy(List<String> forbiddenTools, List<String> allowedOutputTopics, List<IdempotentTool> idempotentTools) {

    public Policy {
        forbiddenTools = forbiddenTools == null ? List.of() : List.copyOf(forbiddenTools);
        allowedOutputTopics = allowedOutputTopics == null ? List.of() : List.copyOf(allowedOutputTopics);
        idempotentTools = idempotentTools == null ? List.of() : List.copyOf(idempotentTools);
    }
}
