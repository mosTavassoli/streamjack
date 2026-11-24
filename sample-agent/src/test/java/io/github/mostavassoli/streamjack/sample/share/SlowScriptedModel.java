package io.github.mostavassoli.streamjack.sample.share;

import io.github.mostavassoli.streamjack.sample.domain.TicketAnalysis;
import io.github.mostavassoli.streamjack.sample.model.ModelInput;
import io.github.mostavassoli.streamjack.sample.model.ScriptedTicketModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** The scripted model, slowed down to stand in for a slow model call, counting how often each ticket is analyzed. */
// Enabled only by the share-group test profiles, never globally.
@Alternative
@ApplicationScoped
public class SlowScriptedModel extends ScriptedTicketModel {

    static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();

    @ConfigProperty(name = "streamjack.test.model-delay-ms")
    long delayMs;

    @Override
    public TicketAnalysis analyze(ModelInput input) {
        CALLS.computeIfAbsent(input.ticket().ticketId(), id -> new AtomicInteger()).incrementAndGet();
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        return super.analyze(input);
    }
}
