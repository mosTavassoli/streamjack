package io.github.mostavassoli.streamjack.core.protocol;

import java.util.Set;

/**
 * Language-neutral names used by the Kafka agent instrumentation contract.
 */
public final class AgentProtocol {

    public static final String AUDIT_TOPIC = "streamjack.audit";
    public static final String RUN_ID_HEADER = "streamjack-run-id";
    public static final String EVENT_ID_HEADER = "streamjack-event-id";
    public static final String ORIGIN_HEADER = "streamjack-origin";
    public static final String RUNNER_ORIGIN = "runner";

    public static final String TOOL_CALL_KIND = "tool-call";
    public static final String EVENT_COMPLETED_KIND = "event-completed";
    public static final String PROCESSED_STATUS = "processed";
    public static final String FAILED_STATUS = "failed";
    public static final String TOOL_NAME_REGEX = "[A-Za-z][A-Za-z0-9_.-]{0,127}";

    public static final Set<String> RESERVED_HEADERS =
            Set.of(RUN_ID_HEADER, EVENT_ID_HEADER, ORIGIN_HEADER);

    private AgentProtocol() {
    }
}
