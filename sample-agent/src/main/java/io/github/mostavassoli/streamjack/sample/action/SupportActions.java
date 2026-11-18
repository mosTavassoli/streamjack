package io.github.mostavassoli.streamjack.sample.action;

import io.github.mostavassoli.streamjack.sample.domain.PublishedReply;
import io.github.mostavassoli.streamjack.sample.messaging.Correlation;
import io.github.mostavassoli.streamjack.sample.messaging.KafkaOutputPublisher;
import io.github.mostavassoli.streamjack.sample.policy.AgentPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SupportActions {

    private final Set<String> followUps = ConcurrentHashMap.newKeySet();
    private final AgentPolicy policy;
    private final KafkaOutputPublisher publisher;

    public SupportActions(AgentPolicy policy, KafkaOutputPublisher publisher) {
        this.policy = policy;
        this.publisher = publisher;
    }

    public void lookupCustomer(Correlation correlation, String customerId) {
        publisher.toolCall(correlation, "lookupCustomer", Map.of("customerId", customerId));
    }

    public void createFollowUp(Correlation correlation, String ticketId) {
        if (policy.deduplicateSideEffects() && !followUps.add(ticketId)) {
            return;
        }
        publisher.toolCall(correlation, "createFollowUp", Map.of("ticketId", ticketId));
    }

    public void publishReply(Correlation correlation, String ticketId, String draft) {
        publisher.toolCall(correlation, "publishReply", Map.of("ticketId", ticketId));
        publisher.published(correlation, new PublishedReply(ticketId, draft, "SIMULATED"));
    }
}
