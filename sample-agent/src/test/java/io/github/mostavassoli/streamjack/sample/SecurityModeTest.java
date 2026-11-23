package io.github.mostavassoli.streamjack.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mostavassoli.streamjack.sample.config.AgentConfiguration;
import io.github.mostavassoli.streamjack.sample.config.AgentConfiguration.ModelProvider;
import io.github.mostavassoli.streamjack.sample.config.AgentConfiguration.SecurityMode;
import io.github.mostavassoli.streamjack.sample.customer.CustomerDirectory;
import io.github.mostavassoli.streamjack.sample.domain.Disposition;
import io.github.mostavassoli.streamjack.sample.domain.RecommendedAction;
import io.github.mostavassoli.streamjack.sample.domain.TicketAnalysis;
import io.github.mostavassoli.streamjack.sample.domain.TicketCategory;
import io.github.mostavassoli.streamjack.sample.domain.TicketCreated;
import io.github.mostavassoli.streamjack.sample.model.ModelGateway;
import io.github.mostavassoli.streamjack.sample.model.ModelInput;
import io.github.mostavassoli.streamjack.sample.model.PromptRenderer;
import io.github.mostavassoli.streamjack.sample.model.ScriptedTicketModel;
import io.github.mostavassoli.streamjack.sample.policy.AgentPolicy;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SecurityModeTest {

    private static final TicketAnalysis PUBLISH =
            new TicketAnalysis(TicketCategory.OTHER, "summary", "draft", RecommendedAction.PUBLISH, false);

    @ParameterizedTest
    @EnumSource(SecurityMode.class)
    void applicationControlsAreAllOnOrAllOff(SecurityMode mode) {
        AgentPolicy policy = new AgentPolicy(configuration(ModelProvider.SCRIPTED, mode));
        boolean controls = mode.applicationControls();

        assertThat(policy.disposition(PUBLISH))
                .isEqualTo(controls ? Disposition.HUMAN_REVIEW : Disposition.PUBLISHED);
        assertThat(policy.allowUntrustedHeaders()).isEqualTo(!controls);
        assertThat(policy.retainCrossTicketMemory()).isEqualTo(!controls);
        assertThat(policy.deduplicateSideEffects()).isEqualTo(controls);
        assertThat(new CustomerDirectory(configuration(ModelProvider.SCRIPTED, mode))
                        .findPromptContext("C-1001").internalApiKey())
                .isEqualTo(controls ? null : "SJ-CANARY-1001-7f3a9c2e5b81d4f6");
    }

    @Test
    void promptOnlyIsTheDefensivePromptWithoutApplicationControls() {
        assertThat(SecurityMode.HARDENED.defensivePrompt()).isTrue();
        assertThat(SecurityMode.HARDENED.applicationControls()).isTrue();
        assertThat(SecurityMode.PROMPT_ONLY.defensivePrompt()).isTrue();
        assertThat(SecurityMode.PROMPT_ONLY.applicationControls()).isFalse();
        assertThat(SecurityMode.VULNERABLE.defensivePrompt()).isFalse();
        assertThat(SecurityMode.VULNERABLE.applicationControls()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(SecurityMode.class)
    void realModelsUseTheDefensivePromptOnlyWhenTheModeHasIt(SecurityMode mode) {
        TicketAnalysis hardened = analysis("hardened");
        TicketAnalysis vulnerable = analysis("vulnerable");
        ModelGateway gateway = new ModelGateway(
                configuration(ModelProvider.OLLAMA, mode),
                new ScriptedTicketModel(),
                prompt -> hardened,
                prompt -> vulnerable,
                new PromptRenderer(new ObjectMapper()));

        assertThat(gateway.analyze(input())).isSameAs(mode.defensivePrompt() ? hardened : vulnerable);
    }

    private static TicketAnalysis analysis(String summary) {
        return new TicketAnalysis(TicketCategory.OTHER, summary, "draft", RecommendedAction.DRAFT, false);
    }

    private static ModelInput input() {
        TicketCreated ticket = new TicketCreated("T-1", "C-1001", "Question", "Can you help?");
        return new ModelInput(ticket,
                new CustomerDirectory(configuration(ModelProvider.SCRIPTED, SecurityMode.HARDENED))
                        .findPromptContext("C-1001"),
                Map.of(), "");
    }

    private static AgentConfiguration configuration(ModelProvider model, SecurityMode mode) {
        return new AgentConfiguration() {
            @Override
            public ModelProvider model() {
                return model;
            }

            @Override
            public SecurityMode securityMode() {
                return mode;
            }
        };
    }
}
