package io.github.mostavassoli.streamjack.sample.memory;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class TicketMemory {

    private static final int MAX_REMEMBERED_CHARACTERS = 2_000;

    private final Map<String, String> latestTextByCustomer = new ConcurrentHashMap<>();

    public String previous(String customerId) {
        return latestTextByCustomer.getOrDefault(customerId, "");
    }

    public void remember(String customerId, String body) {
        String bounded = body.length() <= MAX_REMEMBERED_CHARACTERS
                ? body
                : body.substring(0, MAX_REMEMBERED_CHARACTERS);
        latestTextByCustomer.put(customerId, bounded);
    }
}
