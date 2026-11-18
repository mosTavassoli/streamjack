package io.github.mostavassoli.streamjack.core.scenario;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum EventRole {
    @JsonProperty("benign")
    BENIGN,
    @JsonProperty("attack")
    ATTACK
}
