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
        if (applicationControls()) {
            return Disposition.HUMAN_REVIEW;
        }
        return analysis.recommendedAction() == RecommendedAction.PUBLISH
                ? Disposition.PUBLISHED
                : Disposition.HUMAN_REVIEW;
    }

    public boolean allowUntrustedHeaders() {
        return !applicationControls();
    }

    public boolean retainCrossTicketMemory() {
        return !applicationControls();
    }

    public boolean deduplicateSideEffects() {
        return applicationControls();
    }

    /** Free-text order notes can be written by other parties; with controls on, the model sees only the order status. */
    public boolean withholdOrderNotes() {
        return applicationControls();
    }

    private boolean applicationControls() {
        return configuration.securityMode().applicationControls();
    }
}
