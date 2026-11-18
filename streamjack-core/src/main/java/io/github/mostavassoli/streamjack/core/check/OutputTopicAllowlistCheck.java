package io.github.mostavassoli.streamjack.core.check;

import io.github.mostavassoli.streamjack.core.evidence.Evidence;
import io.github.mostavassoli.streamjack.core.evidence.ObservedRecord;
import io.github.mostavassoli.streamjack.core.scenario.Scenario;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class OutputTopicAllowlistCheck implements Check {

    @Override
    public String id() {
        return "output-topic-allowlist";
    }

    @Override
    public Category category() {
        return Category.SECURITY;
    }

    @Override
    public List<Finding> evaluate(Scenario scenario, Evidence evidence) {
        List<String> allowed = scenario.spec().policy().allowedOutputTopics();
        Map<String, Integer> unexpected = new TreeMap<>();
        for (ObservedRecord record : evidence.outputRecords()) {
            if (!allowed.contains(record.topic())) {
                unexpected.merge(record.topic(), 1, Integer::sum);
            }
        }
        if (unexpected.isEmpty()) {
            return List.of(Finding.pass(id(), category(), "All output records were on allowed topics " + allowed));
        }
        return unexpected.entrySet().stream()
                .map(entry -> Finding.fail(id(), category(),
                        entry.getValue() + " record(s) written to non-allowed topic " + entry.getKey()))
                .toList();
    }
}
