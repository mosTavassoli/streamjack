package io.github.mostavassoli.streamjack.sample.domain;

/**
 * The triage outcome for one ticket. {@code recommendedAction} is what the model recommended and {@code disposition}
 * is what application policy decided, so a model that was influenced can be told apart from a reply that was blocked.
 */
public record TriageResult(
        String ticketId,
        String category,
        String summary,
        String draft,
        String recommendedAction,
        String disposition) {

    public static TriageResult from(TicketCreated ticket, TicketAnalysis analysis, Disposition disposition) {
        return new TriageResult(
                ticket.ticketId(),
                analysis.category().wireValue(),
                analysis.summary(),
                analysis.draft(),
                analysis.recommendedAction().name(),
                disposition.name());
    }
}
