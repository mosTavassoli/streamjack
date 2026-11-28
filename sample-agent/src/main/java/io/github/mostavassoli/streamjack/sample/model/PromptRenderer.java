package io.github.mostavassoli.streamjack.sample.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PromptRenderer {

    private final ObjectMapper json;

    public PromptRenderer(ObjectMapper json) {
        this.json = json;
    }

    public String render(ModelInput input) {
        try {
            return renderTicket(input) + renderOrder(input);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot render model input", e);
        }
    }

    /**
     * The order block, only when the ticket refers to an order, so prompts for tickets without one are unchanged. The
     * order system's fields and the free-text note are rendered separately; an empty note means it was withheld or
     * absent.
     */
    private String renderOrder(ModelInput input) throws JsonProcessingException {
        if (input.order() == null) {
            return "";
        }
        return """

                <order-system-record>
                %s
                </order-system-record>

                <untrusted-order-note>
                %s
                </untrusted-order-note>
                """
                .formatted(
                        json.writeValueAsString(new OrderSystemFields(input.order().orderId(), input.order().status().name())),
                        input.order().note());
    }

    /** A record, so the fields are always serialized in this order: only the note may differ between two orders. */
    private record OrderSystemFields(String orderId, String status) {
    }

    private String renderTicket(ModelInput input) throws JsonProcessingException {
            return """
                    <untrusted-customer-context>
                    %s
                    </untrusted-customer-context>

                    <untrusted-kafka-headers>
                    %s
                    </untrusted-kafka-headers>

                    <prior-untrusted-ticket-text>
                    %s
                    </prior-untrusted-ticket-text>

                    <current-untrusted-ticket-json>
                    %s
                    </current-untrusted-ticket-json>
                    """
                    .formatted(
                            input.customerContext().render(),
                            json.writeValueAsString(input.untrustedHeaders()),
                            input.priorUntrustedTicketText(),
                            json.writeValueAsString(input.ticket()));
    }
}
