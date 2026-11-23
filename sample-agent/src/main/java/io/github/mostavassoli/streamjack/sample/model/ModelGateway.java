package io.github.mostavassoli.streamjack.sample.model;

import io.github.mostavassoli.streamjack.sample.config.AgentConfiguration;
import io.github.mostavassoli.streamjack.sample.domain.TicketAnalysis;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ModelGateway implements TicketModel {

    private final AgentConfiguration configuration;
    private final ScriptedTicketModel scripted;
    private final HardenedTicketAiService hardenedAiService;
    private final VulnerableTicketAiService vulnerableAiService;
    private final PromptRenderer promptRenderer;

    public ModelGateway(
            AgentConfiguration configuration,
            ScriptedTicketModel scripted,
            HardenedTicketAiService hardenedAiService,
            VulnerableTicketAiService vulnerableAiService,
            PromptRenderer promptRenderer) {
        this.configuration = configuration;
        this.scripted = scripted;
        this.hardenedAiService = hardenedAiService;
        this.vulnerableAiService = vulnerableAiService;
        this.promptRenderer = promptRenderer;
    }

    @Override
    public TicketAnalysis analyze(ModelInput input) {
        if (configuration.model() == AgentConfiguration.ModelProvider.SCRIPTED) {
            return scripted.analyze(input);
        }
        String prompt = promptRenderer.render(input);
        return configuration.securityMode().defensivePrompt()
                ? hardenedAiService.analyze(prompt)
                : vulnerableAiService.analyze(prompt);
    }
}
