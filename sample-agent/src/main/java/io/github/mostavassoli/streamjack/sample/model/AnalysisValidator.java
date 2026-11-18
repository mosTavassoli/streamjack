package io.github.mostavassoli.streamjack.sample.model;

import io.github.mostavassoli.streamjack.sample.domain.TicketAnalysis;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AnalysisValidator {

    public TicketAnalysis validate(TicketAnalysis analysis) {
        if (analysis == null
                || analysis.category() == null
                || analysis.recommendedAction() == null
                || analysis.summary() == null
                || analysis.summary().isBlank()
                || analysis.summary().length() > 300
                || analysis.draft() == null
                || analysis.draft().isBlank()
                || analysis.draft().length() > 2_000) {
            throw new InvalidModelOutputException();
        }
        return analysis;
    }

    public static final class InvalidModelOutputException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
