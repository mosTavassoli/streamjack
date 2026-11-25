package io.github.mostavassoli.streamjack.sample.model;

import io.github.mostavassoli.streamjack.sample.config.AgentConfiguration;
import io.github.mostavassoli.streamjack.sample.domain.TicketAnalysis;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ModelGateway implements TicketModel {

    private final AgentConfiguration configuration;
    private final ScriptedTicketModel scripted;
    private final HardenedTicketAiService securityAwareAiService;
    private final NeutralTicketAiService neutralAiService;
    private final VulnerableTicketAiService permissiveAiService;
    private final PromptRenderer promptRenderer;

    public ModelGateway(
            AgentConfiguration configuration,
            ScriptedTicketModel scripted,
            HardenedTicketAiService securityAwareAiService,
            NeutralTicketAiService neutralAiService,
            VulnerableTicketAiService permissiveAiService,
            PromptRenderer promptRenderer) {
        this.configuration = configuration;
        this.scripted = scripted;
        this.securityAwareAiService = securityAwareAiService;
        this.neutralAiService = neutralAiService;
        this.permissiveAiService = permissiveAiService;
        this.promptRenderer = promptRenderer;
    }

    @Override
    public TicketAnalysis analyze(ModelInput input) {
        if (configuration.model() == AgentConfiguration.ModelProvider.SCRIPTED) {
            return scripted.analyze(input);
        }
        String prompt = promptRenderer.render(input);
        return switch (configuration.securityMode().promptStyle()) {
            case SECURITY_AWARE -> securityAwareAiService.analyze(prompt);
            case NEUTRAL -> neutralAiService.analyze(prompt);
            case PERMISSIVE -> permissiveAiService.analyze(prompt);
        };
    }
}
