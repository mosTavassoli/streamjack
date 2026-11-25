package io.github.mostavassoli.streamjack.core.check;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mostavassoli.streamjack.core.evidence.Evidence;
import io.github.mostavassoli.streamjack.core.evidence.ObservedRecord;
import io.github.mostavassoli.streamjack.core.evidence.ToolCall;
import io.github.mostavassoli.streamjack.core.scenario.Scenario;
import io.github.mostavassoli.streamjack.core.scenario.UtilityExpectation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class UtilityCheck implements Check {

    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    @Override
    public String id() {
        return "utility";
    }

    @Override
    public Category category() {
        return Category.UTILITY;
    }

    @Override
    public List<Finding> evaluate(Scenario scenario, Evidence evidence) {
        List<Finding> findings = new ArrayList<>();
        for (UtilityExpectation expectation : scenario.spec().utility()) {
            findings.add(evaluate(expectation, evidence));
        }
        return findings;
    }

    private Finding evaluate(UtilityExpectation expectation, Evidence evidence) {
        if (expectation.tool() != null) {
            return evaluateToolCall(expectation, evidence);
        }
        List<ObservedRecord> candidates = evidence.outputRecords().stream()
                .filter(record -> expectation.topic().equals(record.topic()))
                .filter(record -> expectation.key() == null || expectation.key().equals(record.key()))
                .toList();
        if (candidates.isEmpty()) {
            return Finding.fail(id(), category(), "Expectation " + expectation.id() + ": no record on topic "
                    + expectation.topic() + (expectation.key() == null ? "" : " with key " + expectation.key()));
        }
        for (ObservedRecord record : candidates) {
            if (matches(record, expectation.fields())) {
                return Finding.pass(id(), category(), "Expectation " + expectation.id() + " met on topic " + record.topic());
            }
        }
        return Finding.fail(id(), category(), "Expectation " + expectation.id() + ": " + candidates.size()
                + " candidate record(s) found, none with fields " + expectation.fields());
    }

    /** Met by at least one audited call to the tool whose arguments include the expected values. */
    private Finding evaluateToolCall(UtilityExpectation expectation, Evidence evidence) {
        List<ToolCall> calls = evidence.toolCalls().stream()
                .filter(call -> expectation.tool().equals(call.tool()))
                .toList();
        if (calls.isEmpty()) {
            return Finding.fail(id(), category(),
                    "Expectation " + expectation.id() + ": tool " + expectation.tool() + " was not called");
        }
        for (ToolCall call : calls) {
            if (argumentsMatch(call.arguments(), expectation.arguments())) {
                return Finding.pass(id(), category(),
                        "Expectation " + expectation.id() + " met by a call to " + expectation.tool());
            }
        }
        return Finding.fail(id(), category(), "Expectation " + expectation.id() + ": " + calls.size()
                + " call(s) to " + expectation.tool() + ", none with arguments " + expectation.arguments());
    }

    private static boolean argumentsMatch(JsonNode actual, Map<String, String> expected) {
        for (Map.Entry<String, String> argument : expected.entrySet()) {
            JsonNode value = actual.get(argument.getKey());
            if (!isScalarEqualTo(value, argument.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares a JSON scalar's text with the expected value. Objects and arrays never match: their text is empty,
     * so they would otherwise satisfy an expected empty string.
     */
    private static boolean isScalarEqualTo(JsonNode actual, String expected) {
        return actual != null && !actual.isNull() && actual.isValueNode() && Objects.equals(expected, actual.asText());
    }

    private static boolean matches(ObservedRecord record, Map<String, String> fields) {
        if (fields.isEmpty()) {
            return true;
        }
        JsonNode value;
        try {
            value = record.value() == null ? null : JSON.readTree(record.value());
        } catch (JsonProcessingException e) {
            return false;
        }
        if (value == null || !value.isObject()) {
            return false;
        }
        for (Map.Entry<String, String> field : fields.entrySet()) {
            JsonNode actual = value.get(field.getKey());
            if (!isScalarEqualTo(actual, field.getValue())) {
                return false;
            }
        }
        return true;
    }
}
