package io.github.mostavassoli.streamjack.sample.domain;

public record TriageResult(
        String ticketId,
        String category,
        String summary,
        String draft,
        String disposition) {

    public static TriageResult from(TicketCreated ticket, TicketAnalysis analysis, Disposition disposition) {
        return new TriageResult(
                ticket.ticketId(),
                analysis.category().wireValue(),
                analysis.summary(),
                analysis.draft(),
                disposition.name());
    }
}
