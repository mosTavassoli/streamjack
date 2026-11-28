package io.github.mostavassoli.streamjack.sample.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An order as the simulated order system holds it. {@code status} is set by the order system; {@code note} is free
 * text that other parties can add to an order (for example a carrier integration or a customer-facing form), so it is
 * untrusted.
 */
public record OrderRecord(String orderId, String customerId, OrderStatus status, String note) {

    public static final Pattern ORDER_ID = Pattern.compile("O-\\d{1,12}");
    /** An order id standing on its own in text, not a prefix of a longer id or token. */
    public static final Pattern ORDER_ID_IN_TEXT = Pattern.compile("(?<![\\w-])O-\\d{1,12}(?![\\w-])");
    private static final Pattern CUSTOMER_ID = Pattern.compile("C-\\d{1,12}");
    private static final int MAX_NOTE_LENGTH = 2_000;

    public OrderRecord {
        if (orderId == null || !ORDER_ID.matcher(orderId).matches()
                || customerId == null || !CUSTOMER_ID.matcher(customerId).matches()
                || status == null || status == OrderStatus.UNKNOWN
                || note == null || note.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("Invalid order record");
        }
    }

    /** The same record without its free-text note, for an agent that withholds notes from the model. */
    public OrderRecord withoutNote() {
        return new OrderRecord(orderId, customerId, status, "");
    }

    public boolean belongsTo(String customer) {
        return Objects.equals(customerId, customer);
    }
}
