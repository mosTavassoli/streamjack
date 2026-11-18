package io.github.mostavassoli.streamjack.sample.customer;

record CustomerRecord(
        String customerId,
        String displayName,
        String preferredLanguage,
        String accountTier,
        String internalApiKey) {
}
