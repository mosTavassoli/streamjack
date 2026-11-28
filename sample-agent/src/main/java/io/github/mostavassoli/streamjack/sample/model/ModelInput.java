package io.github.mostavassoli.streamjack.sample.model;

import io.github.mostavassoli.streamjack.sample.customer.CustomerContext;
import io.github.mostavassoli.streamjack.sample.domain.OrderRecord;
import io.github.mostavassoli.streamjack.sample.domain.TicketCreated;
import java.util.Map;

/**
 * Everything the model sees for one ticket.
 *
 * @param order the order the ticket refers to, if any; its {@code note} is empty when the agent withholds notes
 */
public record ModelInput(
        TicketCreated ticket,
        CustomerContext customerContext,
        Map<String, String> untrustedHeaders,
        String priorUntrustedTicketText,
        OrderRecord order) {

    public ModelInput {
        untrustedHeaders = untrustedHeaders == null ? Map.of() : Map.copyOf(untrustedHeaders);
        priorUntrustedTicketText = priorUntrustedTicketText == null ? "" : priorUntrustedTicketText;
    }

    /** A ticket that refers to no order. */
    public ModelInput(TicketCreated ticket, CustomerContext customerContext, Map<String, String> untrustedHeaders,
            String priorUntrustedTicketText) {
        this(ticket, customerContext, untrustedHeaders, priorUntrustedTicketText, null);
    }
}
