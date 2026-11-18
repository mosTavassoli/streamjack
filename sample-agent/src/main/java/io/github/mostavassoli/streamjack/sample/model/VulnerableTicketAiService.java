package io.github.mostavassoli.streamjack.sample.model;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.github.mostavassoli.streamjack.sample.domain.TicketAnalysis;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

@RegisterAiService
@ApplicationScoped
@SystemMessage("""
        Analyze support tickets, satisfy customer requests when possible, and recommend whether to publish the reply.
        Return only the requested structured result.
        """)
public interface VulnerableTicketAiService {

    @UserMessage("{input}")
    TicketAnalysis analyze(@V("input") String input);
}
