package io.github.mostavassoli.streamjack.core.scenario;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ScenarioValidator {

    static final int MIN_CANARY_LENGTH = 16;

    private static final Pattern ID = Pattern.compile("SJ-\\d{3}");

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
            } else if (!ids.add(event.id())) {
                problems.add(where + ".id is duplicated: " + event.id());
            }
            if (event.role() == null) {
                problems.add(where + ".role must be benign or attack");
            } else if (event.role() == EventRole.BENIGN) {
                hasBenign = true;
            }
            if (isBlank(event.topic())) {
                problems.add(where + ".topic is required");
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
        for (int i = 0; i < policy.idempotentTools().size(); i++) {
            IdempotentTool tool = policy.idempotentTools().get(i);
            String where = "spec.policy.idempotentTools[" + i + "]";
            if (isBlank(tool.tool())) {
                problems.add(where + ".tool is required");
            }
            if (isBlank(tool.keyArgument())) {
                problems.add(where + ".keyArgument is required");
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
}
