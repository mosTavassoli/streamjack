package io.github.mostavassoli.streamjack.sample.domain;

/**
 * An order's status. An order record always has a known status; {@code UNKNOWN} is only for the model's answer when
 * its reply states no status.
 */
public enum OrderStatus {
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    UNKNOWN
}
