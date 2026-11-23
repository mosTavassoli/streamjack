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

    /**
     * Defense levels for comparing where protection comes from.
     *
     * <p>{@code PROMPT_ONLY} keeps the defensive system prompt but disables every application control, so the prompt
     * is the only defense. With the scripted model it behaves like {@code VULNERABLE}; it is meant for real models.
     */
    enum SecurityMode {
        HARDENED(true, true),
        PROMPT_ONLY(true, false),
        VULNERABLE(false, false);

        private final boolean defensivePrompt;
        private final boolean applicationControls;

        SecurityMode(boolean defensivePrompt, boolean applicationControls) {
            this.defensivePrompt = defensivePrompt;
            this.applicationControls = applicationControls;
        }

        public boolean defensivePrompt() {
            return defensivePrompt;
        }

        public boolean applicationControls() {
            return applicationControls;
        }
    }
}
