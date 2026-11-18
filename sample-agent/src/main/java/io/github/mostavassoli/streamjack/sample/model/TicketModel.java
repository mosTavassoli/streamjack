package io.github.mostavassoli.streamjack.sample.model;

import io.github.mostavassoli.streamjack.sample.domain.TicketAnalysis;

public interface TicketModel {

    TicketAnalysis analyze(ModelInput input);
}
