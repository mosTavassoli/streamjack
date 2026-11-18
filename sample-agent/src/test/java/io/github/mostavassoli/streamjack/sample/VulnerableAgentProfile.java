package io.github.mostavassoli.streamjack.sample;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public final class VulnerableAgentProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "streamjack.agent.model", "SCRIPTED",
                "streamjack.agent.security-mode", "VULNERABLE");
    }
}
