package io.github.mostavassoli.streamjack.sample.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "streamjack.agent")
public interface AgentConfiguration {

    @WithDefault("SCRIPTED")
    ModelProvider model();

    @WithDefault("HARDENED")
    SecurityMode securityMode();

    @WithDefault("PAYLOAD")
    IdentitySource identitySource();

    /**
     * Where the agent takes the customer identity from. It is independent of the security mode, so identity binding
     * can be tested with cross-ticket memory still enabled.
     */
    enum IdentitySource {
        /**
         * The {@code customerId} in the event payload. Anyone who can write to the ticket topic can claim any
         * customer, load that customer's context, and write into that customer's cross-ticket memory.
         */
        PAYLOAD,
        /**
         * The {@code streamjack-authenticated-customer} header, set by a trusted gateway that authenticated the
         * customer. It is only trustworthy when Kafka ACLs let no one but the gateway write to the ticket topic. A
         * ticket without exactly one valid header, or whose payload claims a different customer, is rejected.
         */
        CHANNEL
    }

    /**
     * Where model calls go. {@code OLLAMA} and {@code OPENAI} use the chat model selected at build time with
     * {@code quarkus.langchain4j.chat-model.provider}; this setting only switches between the scripted model and that
     * real model.
     */
    enum ModelProvider {
        SCRIPTED,
        OLLAMA,
        OPENAI
    }

    /** The system prompt a mode uses. */
    enum PromptStyle {
        /** Pushes the model to satisfy customers and publish; used to show an undefended agent. */
        PERMISSIVE,
        /** Describes the task and when publishing is appropriate, with no security guidance. */
        NEUTRAL,
        /** The neutral task plus security guidance about untrusted input and secrets. */
        SECURITY_AWARE
    }

    /**
     * Defense levels for comparing where protection comes from.
     *
     * <p>{@code NEUTRAL} and {@code PROMPT_ONLY} share the same task description and differ only in security guidance,
     * so comparing them measures what the guidance adds. {@code PROMPT_ONLY} and {@code HARDENED} share the same prompt
     * and differ in application controls. With the scripted model, every mode without controls behaves like
     * {@code VULNERABLE}; the prompt modes are meant for real models.
     */
    enum SecurityMode {
        HARDENED(PromptStyle.SECURITY_AWARE, true),
        PROMPT_ONLY(PromptStyle.SECURITY_AWARE, false),
        NEUTRAL(PromptStyle.NEUTRAL, false),
        VULNERABLE(PromptStyle.PERMISSIVE, false);

        private final PromptStyle promptStyle;
        private final boolean applicationControls;

        SecurityMode(PromptStyle promptStyle, boolean applicationControls) {
            this.promptStyle = promptStyle;
            this.applicationControls = applicationControls;
        }

        public PromptStyle promptStyle() {
            return promptStyle;
        }

        public boolean applicationControls() {
            return applicationControls;
        }
    }
}
