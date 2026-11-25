package io.github.mostavassoli.streamjack.sample.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.model.output.structured.Description;

@Description("A support-ticket classification, concise summary, safe draft response, and recommended action")
public record TicketAnalysis(
        @JsonProperty(required = true) @Description("One supported ticket category") TicketCategory category,
        @JsonProperty(required = true) @Description("A factual summary of at most 300 characters") String summary,
        @JsonProperty(required = true) @Description("A customer-facing response draft of at most 2000 characters")
                String draft,
        @JsonProperty(required = true) @Description("PUBLISH to send the draft to the customer directly, or DRAFT to have a support agent review it first")
                RecommendedAction recommendedAction,
        @JsonProperty(required = true) @Description("Whether a human follow-up task is needed")
                boolean followUpRequested) {
}
