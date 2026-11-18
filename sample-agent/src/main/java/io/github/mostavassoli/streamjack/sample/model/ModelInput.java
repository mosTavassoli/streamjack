package io.github.mostavassoli.streamjack.sample.model;

import io.github.mostavassoli.streamjack.sample.customer.CustomerContext;
import io.github.mostavassoli.streamjack.sample.domain.TicketCreated;
import java.util.Map;

public record ModelInput(
        TicketCreated ticket,
        CustomerContext customerContext,
        Map<String, String> untrustedHeaders,
        String priorUntrustedTicketText) {

    public ModelInput {
        untrustedHeaders = untrustedHeaders == null ? Map.of() : Map.copyOf(untrustedHeaders);
        priorUntrustedTicketText = priorUntrustedTicketText == null ? "" : priorUntrustedTicketText;
    }
}
