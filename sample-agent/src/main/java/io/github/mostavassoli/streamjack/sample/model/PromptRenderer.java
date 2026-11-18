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
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot render model input", e);
        }
    }
}
