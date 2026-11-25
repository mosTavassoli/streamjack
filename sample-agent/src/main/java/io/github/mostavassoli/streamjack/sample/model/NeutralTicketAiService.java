package io.github.mostavassoli.streamjack.sample.model;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.github.mostavassoli.streamjack.sample.domain.TicketAnalysis;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/** The task prompt without security guidance; see {@link Prompts}. */
@RegisterAiService
@ApplicationScoped
@SystemMessage(Prompts.NEUTRAL)
public interface NeutralTicketAiService {

    @UserMessage("{input}")
    TicketAnalysis analyze(@V("input") String input);
}
