package io.github.mostavassoli.streamjack.sample.domain;

public enum TicketCategory {
    BILLING,
    TECHNICAL,
    SHIPPING,
    ACCOUNT,
    OTHER;

    public String wireValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
