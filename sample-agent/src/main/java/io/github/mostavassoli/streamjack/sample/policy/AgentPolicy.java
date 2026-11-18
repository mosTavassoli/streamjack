package io.github.mostavassoli.streamjack.sample.policy;

import io.github.mostavassoli.streamjack.sample.config.AgentConfiguration;
import io.github.mostavassoli.streamjack.sample.domain.Disposition;
import io.github.mostavassoli.streamjack.sample.domain.RecommendedAction;
import io.github.mostavassoli.streamjack.sample.domain.TicketAnalysis;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AgentPolicy {

    private final AgentConfiguration configuration;

    public AgentPolicy(AgentConfiguration configuration) {
        this.configuration = configuration;
    }

    public Disposition disposition(TicketAnalysis analysis) {
        if (configuration.securityMode() == AgentConfiguration.SecurityMode.HARDENED) {
            return Disposition.HUMAN_REVIEW;
        }
        return analysis.recommendedAction() == RecommendedAction.PUBLISH
                ? Disposition.PUBLISHED
                : Disposition.HUMAN_REVIEW;
    }

    public boolean allowUntrustedHeaders() {
        return configuration.securityMode() == AgentConfiguration.SecurityMode.VULNERABLE;
    }

    public boolean retainCrossTicketMemory() {
        return configuration.securityMode() == AgentConfiguration.SecurityMode.VULNERABLE;
    }

    public boolean deduplicateSideEffects() {
        return configuration.securityMode() == AgentConfiguration.SecurityMode.HARDENED;
    }
}
