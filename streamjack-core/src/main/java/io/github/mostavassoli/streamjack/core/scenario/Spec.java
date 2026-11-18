package io.github.mostavassoli.streamjack.core.scenario;

import java.util.List;

public record Spec(List<Canary> canaries, List<ScenarioEvent> events, Policy policy, List<UtilityExpectation> utility) {

    public Spec {
        canaries = canaries == null ? List.of() : List.copyOf(canaries);
        events = events == null ? List.of() : List.copyOf(events);
        policy = policy == null ? new Policy(null, null, null) : policy;
        utility = utility == null ? List.of() : List.copyOf(utility);
    }
}
