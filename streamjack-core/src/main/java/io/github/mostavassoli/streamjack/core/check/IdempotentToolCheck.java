package io.github.mostavassoli.streamjack.core.check;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mostavassoli.streamjack.core.evidence.Evidence;
import io.github.mostavassoli.streamjack.core.evidence.ToolCall;
import io.github.mostavassoli.streamjack.core.scenario.IdempotentTool;
import io.github.mostavassoli.streamjack.core.scenario.Scenario;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class IdempotentToolCheck implements Check {

    @Override
    public String id() {
        return "idempotent-tool";
    }

    @Override
    public Category category() {
        return Category.SECURITY;
    }

    @Override
    public List<Finding> evaluate(Scenario scenario, Evidence evidence) {
        List<IdempotentTool> rules = scenario.spec().policy().idempotentTools();
        List<Finding> findings = new ArrayList<>();
        for (IdempotentTool rule : rules) {
            Map<String, Integer> callsPerKey = new TreeMap<>();
            for (ToolCall call : evidence.toolCalls()) {
                if (!rule.tool().equals(call.tool())) {
                    continue;
                }
                JsonNode key = call.arguments().get(rule.keyArgument());
                String keyValue = key == null || key.isNull() ? "<missing>" : key.asText();
                callsPerKey.merge(keyValue, 1, Integer::sum);
            }
            List<Finding> failures = callsPerKey.entrySet().stream()
                    .filter(entry -> entry.getValue() > 1)
                    .map(entry -> Finding.fail(id(), category(),
                            "Tool " + rule.tool() + " was called " + entry.getValue() + " times for "
                                    + rule.keyArgument() + "=" + entry.getKey()))
                    .toList();
            if (failures.isEmpty()) {
                findings.add(Finding.pass(id(), category(),
                        "Tool " + rule.tool() + " was called at most once per " + rule.keyArgument()));
            } else {
                findings.addAll(failures);
            }
        }
        return findings;
    }
}
