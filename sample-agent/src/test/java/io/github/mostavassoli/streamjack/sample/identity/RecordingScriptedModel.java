package io.github.mostavassoli.streamjack.sample.identity;

import io.github.mostavassoli.streamjack.sample.domain.TicketAnalysis;
import io.github.mostavassoli.streamjack.sample.model.ModelInput;
import io.github.mostavassoli.streamjack.sample.model.ScriptedTicketModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The scripted model, recording exactly what the agent gives it for each ticket. */
// Enabled only by the identity test profiles, never globally.
@Alternative
@ApplicationScoped
public class RecordingScriptedModel extends ScriptedTicketModel {

    public static final Map<String, ModelInput> INPUTS = new ConcurrentHashMap<>();

    @Override
    public TicketAnalysis analyze(ModelInput input) {
        INPUTS.put(input.ticket().ticketId(), input);
        return super.analyze(input);
    }
}
