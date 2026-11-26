package io.github.mostavassoli.streamjack.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mostavassoli.streamjack.core.evidence.ObservedRecord;
import io.github.mostavassoli.streamjack.core.evidence.ToolCall;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes the raw evidence of one scenario run (correlated output records and audited tool calls) as JSON, for
 * analyses beyond the built-in checks. The content is agent output: untrusted, and meant for synthetic test data only.
 * The file is written to a temporary name and then moved, so a reader never sees a partial file.
 */
public final class EvidenceWriter {

    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public Path write(ScenarioExecution execution, Path directory) throws IOException {
        Files.createDirectories(directory);
        ObjectNode root = json.createObjectNode();
        root.put("scenarioId", execution.result().scenarioId());
        root.put("runId", execution.runId());
        root.put("execution", execution.result().execution().name());
        root.put("durationMillis", execution.duration().toMillis());
        ArrayNode records = root.putArray("outputRecords");
        for (ObservedRecord record : execution.evidence().outputRecords()) {
            ObjectNode node = records.addObject();
            node.put("topic", record.topic());
            node.put("key", record.key());
            node.set("headers", json.valueToTree(record.headers()));
            node.put("value", record.value());
        }
        ArrayNode calls = root.putArray("toolCalls");
        for (ToolCall call : execution.evidence().toolCalls()) {
            ObjectNode node = calls.addObject();
            node.put("tool", call.tool());
            node.set("arguments", call.arguments());
            node.put("sourceEventId", call.sourceEventId());
        }
        Path target = directory.resolve(execution.result().scenarioId() + ".json");
        Path temporary = directory.resolve(execution.result().scenarioId() + ".json.tmp");
        json.writeValue(temporary.toFile(), root);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }
}
