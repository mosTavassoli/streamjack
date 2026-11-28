package io.github.mostavassoli.streamjack.sample.messaging;

import io.github.mostavassoli.streamjack.core.protocol.AgentProtocol;
import io.github.mostavassoli.streamjack.sample.policy.AgentPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

@ApplicationScoped
public class HeaderPolicy {

    /** Set by the trusted gateway with the customer it authenticated; see {@code IdentitySource.CHANNEL}. */
    public static final String AUTHENTICATED_CUSTOMER_HEADER = "streamjack-authenticated-customer";

    private static final java.util.regex.Pattern CUSTOMER_ID = java.util.regex.Pattern.compile("C-\\d{1,12}");
    private static final int MAX_HEADER_COUNT = 32;
    private static final int MAX_HEADER_BYTES = 8_192;

    private final AgentPolicy policy;

    public HeaderPolicy(AgentPolicy policy) {
        this.policy = policy;
    }

    public Map<String, String> modelHeaders(Headers headers) {
        if (!policy.allowUntrustedHeaders()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        int count = 0;
        for (Header header : headers) {
            if (AgentProtocol.RESERVED_HEADERS.contains(header.key()) || AUTHENTICATED_CUSTOMER_HEADER.equals(header.key())) {
                continue;
            }
            if (++count > MAX_HEADER_COUNT || header.value() == null || header.value().length > MAX_HEADER_BYTES) {
                throw new InvalidHeaderException();
            }
            result.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return Map.copyOf(result);
    }

    public Correlation correlation(Headers headers) {
        String runId = requiredControlHeader(headers, AgentProtocol.RUN_ID_HEADER);
        String eventId = requiredControlHeader(headers, AgentProtocol.EVENT_ID_HEADER);
        String origin = requiredControlHeader(headers, AgentProtocol.ORIGIN_HEADER);
        if (!AgentProtocol.RUNNER_ORIGIN.equals(origin)) {
            throw new InvalidHeaderException();
        }
        return new Correlation(runId, eventId);
    }

    /**
     * The customer the gateway authenticated: exactly one header with a valid customer id.
     *
     * @throws InvalidHeaderException if it is missing, repeated, or malformed
     */
    public String authenticatedCustomer(Headers headers) {
        String value = requiredControlHeader(headers, AUTHENTICATED_CUSTOMER_HEADER);
        if (!CUSTOMER_ID.matcher(value).matches()) {
            throw new InvalidHeaderException();
        }
        return value;
    }

    private static String requiredControlHeader(Headers headers, String name) {
        Header header = null;
        int count = 0;
        for (Header candidate : headers.headers(name)) {
            header = candidate;
            count++;
        }
        if (count != 1 || header == null || header.value() == null || header.value().length > 128) {
            throw new InvalidHeaderException();
        }
        String value = new String(header.value(), StandardCharsets.UTF_8);
        if (value.isBlank()) {
            throw new InvalidHeaderException();
        }
        return value;
    }

    public static final class InvalidHeaderException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
