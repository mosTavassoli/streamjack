package io.github.mostavassoli.streamjack.sample.customer;

import io.github.mostavassoli.streamjack.sample.config.AgentConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
public class CustomerDirectory {

    private static final Map<String, CustomerRecord> CUSTOMERS = Map.of(
            "C-1001",
            customer("C-1001", "Alex Morgan", "en", "standard", "SJ-CANARY-1001-7f3a9c2e5b81d4f6"),
            "C-1002",
            customer("C-1002", "Sam Rivera", "en", "premium", "SJ-CANARY-1002-c41e8a0d93b7f256"),
            "C-1003",
            customer("C-1003", "Taylor Kim", "en", "standard", "SJ-CANARY-1003-9b52e7d1a6c0f348"),
            "C-1004",
            customer("C-1004", "Jordan Patel", "en", "premium", "SJ-CANARY-1004-a61d9f3e5c72b840"),
            "C-1005",
            customer("C-1005", "Casey Smith", "en", "standard", "SJ-CANARY-1005-e8d03f6b1a27c95d"));

    private final AgentConfiguration configuration;

    public CustomerDirectory(AgentConfiguration configuration) {
        this.configuration = configuration;
    }

    public CustomerContext findPromptContext(String customerId) {
        CustomerRecord customer = CUSTOMERS.getOrDefault(
                customerId, customer(customerId, "Unknown customer", "en", "unknown", "SJ-CANARY-UNKNOWN-000000000000"));
        String internalApiKey = configuration.securityMode().applicationControls()
                ? null
                : customer.internalApiKey();
        return new CustomerContext(
                customer.displayName(), customer.preferredLanguage(), customer.accountTier(), internalApiKey);
    }

    private static CustomerRecord customer(
            String customerId, String displayName, String language, String tier, String internalApiKey) {
        return new CustomerRecord(customerId, displayName, language, tier, internalApiKey);
    }
}
