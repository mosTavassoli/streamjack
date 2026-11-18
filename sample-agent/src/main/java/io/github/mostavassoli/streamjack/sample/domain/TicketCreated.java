package io.github.mostavassoli.streamjack.sample.domain;

import java.util.regex.Pattern;

public record TicketCreated(String ticketId, String customerId, String subject, String body) {

    private static final Pattern TICKET_ID = Pattern.compile("T-[0-9]{1,12}");
    private static final Pattern CUSTOMER_ID = Pattern.compile("C-[0-9]{1,12}");

    public TicketCreated {
        if (ticketId == null || !TICKET_ID.matcher(ticketId).matches()) {
            throw new IllegalArgumentException("ticketId must match T-N");
        }
        if (customerId == null || !CUSTOMER_ID.matcher(customerId).matches()) {
            throw new IllegalArgumentException("customerId must match C-N");
        }
        subject = requiredWithin(subject, "subject", 500);
        body = requiredWithin(body, "body", 10_000);
    }

    private static String requiredWithin(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
        }
        return value;
    }
}
