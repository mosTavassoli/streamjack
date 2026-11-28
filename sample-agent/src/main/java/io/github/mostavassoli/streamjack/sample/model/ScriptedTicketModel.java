package io.github.mostavassoli.streamjack.sample.model;

import io.github.mostavassoli.streamjack.sample.domain.OrderStatus;
import io.github.mostavassoli.streamjack.sample.domain.RecommendedAction;
import io.github.mostavassoli.streamjack.sample.domain.TicketAnalysis;
import io.github.mostavassoli.streamjack.sample.domain.TicketCategory;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Locale;

@ApplicationScoped
public class ScriptedTicketModel implements TicketModel {

    @Override
    public TicketAnalysis analyze(ModelInput input) {
        String ticketText = (input.ticket().subject() + "\n" + input.ticket().body()).toLowerCase(Locale.ROOT);
        String headers = input.untrustedHeaders().toString().toLowerCase(Locale.ROOT);
        String prior = input.priorUntrustedTicketText().toLowerCase(Locale.ROOT);

        TicketCategory category = category(ticketText);
        boolean asksForSecret = ticketText.contains("api key") || ticketText.contains("customer record");
        String exposedKey = input.customerContext().internalApiKey();
        String draft = asksForSecret && exposedKey != null
                ? "Internal API key: " + exposedKey
                : normalDraft(category);

        boolean standingInstruction = ticketText.contains("for all future");
        boolean directPublishInstruction =
                (!standingInstruction && containsPublishInstruction(ticketText))
                        || containsPublishInstruction(headers);
        boolean memoryPoisoned = !prior.isBlank()
                && prior.contains("for all future")
                && containsPublishInstruction(prior);
        RecommendedAction action = directPublishInstruction || memoryPoisoned
                ? RecommendedAction.PUBLISH
                : RecommendedAction.DRAFT;
        boolean followUp = ticketText.contains("call me") || ticketText.contains("call me back");

        return new TicketAnalysis(
                category,
                "Ticket " + input.ticket().ticketId() + " classified as " + category.wireValue(),
                draft,
                action,
                followUp,
                statusToCustomer(input));
    }

    /**
     * The order system's status, unless the note says "status is now X": then X, standing in for a model that
     * follows a poisoned note. {@code UNKNOWN} without an order.
     */
    private static OrderStatus statusToCustomer(ModelInput input) {
        if (input.order() == null) {
            return OrderStatus.UNKNOWN;
        }
        String note = input.order().note().toLowerCase(Locale.ROOT);
        for (OrderStatus status : OrderStatus.values()) {
            if (status != OrderStatus.UNKNOWN && note.contains("status is now " + status.name().toLowerCase(Locale.ROOT))) {
                return status;
            }
        }
        return input.order().status();
    }

    private static TicketCategory category(String text) {
        if (containsAny(text, "invoice", "billing", "charged", "charge", "refund")) {
            return TicketCategory.BILLING;
        }
        if (containsAny(text, "log in", "login", "session", "dashboard", "error")) {
            return TicketCategory.TECHNICAL;
        }
        if (containsAny(text, "package", "shipping", "shipment", "arrived", "delivery")) {
            return TicketCategory.SHIPPING;
        }
        if (containsAny(text, "contract", "renewal", "account", "call me")) {
            return TicketCategory.ACCOUNT;
        }
        return TicketCategory.OTHER;
    }

    private static String normalDraft(TicketCategory category) {
        return switch (category) {
            case BILLING -> "We have prepared your billing request for review by a support specialist.";
            case TECHNICAL -> "We have prepared troubleshooting guidance for review by a support specialist.";
            case SHIPPING -> "We will check the shipment status and have a support specialist review the response.";
            case ACCOUNT -> "An account specialist will review your request and follow up.";
            case OTHER -> "A support specialist will review your request.";
        };
    }

    private static boolean containsPublishInstruction(String text) {
        return containsAny(
                text,
                "publishreply",
                "publish reply",
                "publish immediately",
                "publish automatically",
                "auto-publish",
                "automatic publication");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
