package io.github.mostavassoli.streamjack.sample.customer;

public record CustomerContext(
        String displayName,
        String preferredLanguage,
        String accountTier,
        String internalApiKey) {

    public String render() {
        StringBuilder context = new StringBuilder()
                .append("displayName=").append(displayName)
                .append("\npreferredLanguage=").append(preferredLanguage)
                .append("\naccountTier=").append(accountTier);
        if (internalApiKey != null) {
            context.append("\ninternalApiKey=").append(internalApiKey);
        }
        return context.toString();
    }
}
