package io.github.mostavassoli.streamjack.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mostavassoli.streamjack.sample.config.AgentConfiguration;
import io.github.mostavassoli.streamjack.sample.config.AgentConfiguration.ModelProvider;
import io.github.mostavassoli.streamjack.sample.config.AgentConfiguration.PromptStyle;
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
            new TicketAnalysis(TicketCategory.OTHER, "summary", "draft", RecommendedAction.PUBLISH, false,
                    io.github.mostavassoli.streamjack.sample.domain.OrderStatus.UNKNOWN);

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
    void eachComparisonChangesOneLayer() {
        // NEUTRAL vs PROMPT_ONLY: only the security guidance differs.
        assertThat(SecurityMode.NEUTRAL.promptStyle()).isEqualTo(PromptStyle.NEUTRAL);
        assertThat(SecurityMode.PROMPT_ONLY.promptStyle()).isEqualTo(PromptStyle.SECURITY_AWARE);
        assertThat(SecurityMode.NEUTRAL.applicationControls()).isFalse();
        assertThat(SecurityMode.PROMPT_ONLY.applicationControls()).isFalse();
        // PROMPT_ONLY vs HARDENED: only the application controls differ.
        assertThat(SecurityMode.HARDENED.promptStyle()).isEqualTo(PromptStyle.SECURITY_AWARE);
        assertThat(SecurityMode.HARDENED.applicationControls()).isTrue();
        assertThat(SecurityMode.VULNERABLE.promptStyle()).isEqualTo(PromptStyle.PERMISSIVE);
        assertThat(SecurityMode.VULNERABLE.applicationControls()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(SecurityMode.class)
    void realModelsUseThePromptOfTheMode(SecurityMode mode) {
        TicketAnalysis securityAware = analysis("security-aware");
        TicketAnalysis neutral = analysis("neutral");
        TicketAnalysis permissive = analysis("permissive");
        ModelGateway gateway = new ModelGateway(
                configuration(ModelProvider.OPENAI, mode),
                new ScriptedTicketModel(),
                prompt -> securityAware,
                prompt -> neutral,
                prompt -> permissive,
                new PromptRenderer(new ObjectMapper()));

        TicketAnalysis expected = switch (mode.promptStyle()) {
            case SECURITY_AWARE -> securityAware;
            case NEUTRAL -> neutral;
            case PERMISSIVE -> permissive;
        };
        assertThat(gateway.analyze(input())).isSameAs(expected);
    }

    private static TicketAnalysis analysis(String summary) {
        return new TicketAnalysis(TicketCategory.OTHER, summary, "draft", RecommendedAction.DRAFT, false,
                io.github.mostavassoli.streamjack.sample.domain.OrderStatus.UNKNOWN);
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

            @Override
            public IdentitySource identitySource() {
                return IdentitySource.PAYLOAD;
            }

            @Override
            public boolean orderLookup() {
                return false;
            }
        };
    }
}
