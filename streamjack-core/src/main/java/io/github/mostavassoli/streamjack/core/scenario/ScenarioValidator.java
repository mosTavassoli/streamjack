package io.github.mostavassoli.streamjack.core.scenario;

import io.github.mostavassoli.streamjack.core.protocol.AgentProtocol;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ScenarioValidator {

    static final int MIN_CANARY_LENGTH = 16;

    private static final Pattern ID = Pattern.compile("SJ-\\d{3}");
    private static final Pattern KAFKA_TOPIC = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern TOOL_NAME = Pattern.compile(AgentProtocol.TOOL_NAME_REGEX);
    private static final int MAX_TOPIC_LENGTH = 249;
    private static final int MAX_EVENT_ID_LENGTH = 128;
    private static final int MAX_HEADER_COUNT = 32;
    private static final int MAX_HEADER_NAME_LENGTH = 256;
    private static final int MAX_HEADER_VALUE_LENGTH = 8_192;

    private ScenarioValidator() {
    }

    public static List<String> validate(Scenario scenario) {
        List<String> problems = new ArrayList<>();
        if (!Scenario.API_VERSION.equals(scenario.apiVersion())) {
            problems.add("apiVersion must be " + Scenario.API_VERSION + ", was " + scenario.apiVersion());
        }
        if (!Scenario.KIND.equals(scenario.kind())) {
            problems.add("kind must be " + Scenario.KIND + ", was " + scenario.kind());
        }
        Metadata metadata = scenario.metadata();
        if (metadata == null) {
            problems.add("metadata is required");
        } else {
            if (metadata.id() == null || !ID.matcher(metadata.id()).matches()) {
                problems.add("metadata.id must match SJ-NNN, was " + metadata.id());
            }
            if (isBlank(metadata.title())) {
                problems.add("metadata.title is required");
            }
        }
        Spec spec = scenario.spec();
        if (spec == null) {
            problems.add("spec is required");
            return problems;
        }
        validateEvents(spec, problems);
        validateCanaries(spec, problems);
        validatePolicy(spec.policy(), problems);
        validateUtility(spec, problems);
        return problems;
    }

    private static void validateEvents(Spec spec, List<String> problems) {
        if (spec.events().isEmpty()) {
            problems.add("spec.events must contain at least one event");
        }
        Set<String> ids = new HashSet<>();
        boolean hasBenign = false;
        for (int i = 0; i < spec.events().size(); i++) {
            ScenarioEvent event = spec.events().get(i);
            String where = "spec.events[" + i + "]";
            if (isBlank(event.id())) {
                problems.add(where + ".id is required");
            } else if (event.id().length() > MAX_EVENT_ID_LENGTH) {
                problems.add(where + ".id must not exceed " + MAX_EVENT_ID_LENGTH + " characters");
            } else if (!ids.add(event.id())) {
                problems.add(where + ".id is duplicated: " + event.id());
            }
            if (event.role() == null) {
                problems.add(where + ".role must be benign or attack");
            } else if (event.role() == EventRole.BENIGN) {
                hasBenign = true;
            }
            if (!isKafkaTopic(event.topic())) {
                problems.add(where + ".topic must be a valid Kafka topic");
            } else if (AgentProtocol.AUDIT_TOPIC.equals(event.topic())) {
                problems.add(where + ".topic must not use reserved audit topic " + AgentProtocol.AUDIT_TOPIC);
            }
            if (event.headers().size() > MAX_HEADER_COUNT) {
                problems.add(where + ".headers must not contain more than " + MAX_HEADER_COUNT + " entries");
            }
            for (var header : event.headers().entrySet()) {
                String name = header.getKey();
                String value = header.getValue();
                if (isBlank(name) || name.length() > MAX_HEADER_NAME_LENGTH) {
                    problems.add(where + ".headers contains an invalid header name");
                    continue;
                }
                if (AgentProtocol.RESERVED_HEADERS.contains(name)) {
                    problems.add(where + ".headers must not set reserved control header " + name);
                }
                if (value == null || value.length() > MAX_HEADER_VALUE_LENGTH) {
                    problems.add(where + ".headers." + name + " must not exceed "
                            + MAX_HEADER_VALUE_LENGTH + " characters");
                }
            }
            if (event.value() == null || !event.value().isObject()) {
                problems.add(where + ".value must be a JSON object");
            }
        }
        if (!spec.events().isEmpty() && !hasBenign) {
            problems.add("spec.events must contain at least one benign event so utility can be measured");
        }
    }

    private static void validateCanaries(Spec spec, List<String> problems) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < spec.canaries().size(); i++) {
            Canary canary = spec.canaries().get(i);
            String where = "spec.canaries[" + i + "]";
            if (isBlank(canary.id())) {
                problems.add(where + ".id is required");
            } else if (!ids.add(canary.id())) {
                problems.add(where + ".id is duplicated: " + canary.id());
            }
            if (canary.value() == null || canary.value().length() < MIN_CANARY_LENGTH) {
                problems.add(where + ".value must be at least " + MIN_CANARY_LENGTH + " characters");
                continue;
            }
            for (ScenarioEvent event : spec.events()) {
                if (eventContains(event, canary.value())) {
                    problems.add(where + " value appears in event " + event.id() + "; canaries must only be reachable through agent tools");
                }
            }
        }
    }

    private static void validatePolicy(Policy policy, List<String> problems) {
        if (policy.allowedOutputTopics().isEmpty()) {
            problems.add("spec.policy.allowedOutputTopics must list at least one topic");
        }
        Set<String> outputTopics = new HashSet<>();
        for (String topic : policy.allowedOutputTopics()) {
            if (!isKafkaTopic(topic)) {
                problems.add("spec.policy.allowedOutputTopics contains an invalid Kafka topic");
            } else if (AgentProtocol.AUDIT_TOPIC.equals(topic)) {
                problems.add("spec.policy.allowedOutputTopics must not contain reserved audit topic "
                        + AgentProtocol.AUDIT_TOPIC);
            } else if (!outputTopics.add(topic)) {
                problems.add("spec.policy.allowedOutputTopics contains duplicate topic " + topic);
            }
        }
        Set<String> forbiddenTools = new HashSet<>();
        for (String tool : policy.forbiddenTools()) {
            if (!isToolName(tool)) {
                problems.add("spec.policy.forbiddenTools contains an invalid tool name");
            } else if (!forbiddenTools.add(tool)) {
                problems.add("spec.policy.forbiddenTools contains duplicate tool " + tool);
            }
        }
        Set<String> idempotentTools = new HashSet<>();
        for (int i = 0; i < policy.idempotentTools().size(); i++) {
            IdempotentTool tool = policy.idempotentTools().get(i);
            String where = "spec.policy.idempotentTools[" + i + "]";
            if (!isToolName(tool.tool())) {
                problems.add(where + ".tool must be a valid tool name");
            }
            if (isBlank(tool.keyArgument())) {
                problems.add(where + ".keyArgument is required");
            }
            if (isToolName(tool.tool()) && !isBlank(tool.keyArgument())) {
                String identity = tool.tool() + "\u0000" + tool.keyArgument();
                if (!idempotentTools.add(identity)) {
                    problems.add(where + " duplicates an earlier idempotency rule");
                }
            }
        }
    }

    private static void validateUtility(Spec spec, List<String> problems) {
        if (spec.utility().isEmpty()) {
            problems.add("spec.utility must contain at least one expectation");
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < spec.utility().size(); i++) {
            UtilityExpectation expectation = spec.utility().get(i);
            String where = "spec.utility[" + i + "]";
            if (isBlank(expectation.id())) {
                problems.add(where + ".id is required");
            } else if (!ids.add(expectation.id())) {
                problems.add(where + ".id is duplicated: " + expectation.id());
            }
            if (isBlank(expectation.topic())) {
                problems.add(where + ".topic is required");
            } else if (!spec.policy().allowedOutputTopics().contains(expectation.topic())) {
                problems.add(where + ".topic " + expectation.topic() + " is not an allowed output topic");
            }
        }
    }

    private static boolean eventContains(ScenarioEvent event, String needle) {
        if (event.key() != null && event.key().contains(needle)) {
            return true;
        }
        if (event.headers().values().stream().anyMatch(value -> value != null && value.contains(needle))) {
            return true;
        }
        return event.value() != null && event.value().toString().contains(needle);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isKafkaTopic(String topic) {
        return !isBlank(topic)
                && topic.length() <= MAX_TOPIC_LENGTH
                && !".".equals(topic)
                && !"..".equals(topic)
                && KAFKA_TOPIC.matcher(topic).matches();
    }

    private static boolean isToolName(String tool) {
        return tool != null && TOOL_NAME.matcher(tool).matches();
    }
}
