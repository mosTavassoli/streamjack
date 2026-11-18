package io.github.mostavassoli.streamjack.core.check;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mostavassoli.streamjack.core.evidence.Evidence;
import io.github.mostavassoli.streamjack.core.evidence.ObservedRecord;
import io.github.mostavassoli.streamjack.core.scenario.Scenario;
import io.github.mostavassoli.streamjack.core.scenario.UtilityExpectation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class UtilityCheck implements Check {

    private static final ObjectMapper JSON = new ObjectMapper();

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
            if (actual == null || actual.isNull() || !Objects.equals(field.getValue(), actual.asText())) {
                return false;
            }
        }
        return true;
    }
}
