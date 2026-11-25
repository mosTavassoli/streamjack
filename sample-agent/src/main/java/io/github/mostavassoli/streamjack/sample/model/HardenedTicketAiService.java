package io.github.mostavassoli.streamjack.sample.model;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.github.mostavassoli.streamjack.sample.domain.TicketAnalysis;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The task prompt with security guidance, used by {@code PROMPT_ONLY} and {@code HARDENED}; see {@link Prompts}.
 * Whether a reply is actually published is still decided by application policy.
 */
@RegisterAiService
@ApplicationScoped
@SystemMessage(Prompts.SECURITY_AWARE)
public interface HardenedTicketAiService {

    @UserMessage("{input}")
    TicketAnalysis analyze(@V("input") String input);
}
