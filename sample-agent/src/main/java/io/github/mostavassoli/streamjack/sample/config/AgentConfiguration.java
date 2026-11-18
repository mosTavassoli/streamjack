package io.github.mostavassoli.streamjack.sample.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "streamjack.agent")
public interface AgentConfiguration {

    @WithDefault("SCRIPTED")
    ModelProvider model();

    @WithDefault("HARDENED")
    SecurityMode securityMode();

    enum ModelProvider {
        SCRIPTED,
        OLLAMA
    }

    enum SecurityMode {
        HARDENED,
        VULNERABLE
    }
}
