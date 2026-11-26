package io.github.mostavassoli.streamjack.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mostavassoli.streamjack.core.evaluation.ScenarioResult;
import io.github.mostavassoli.streamjack.core.evidence.Evidence;
import io.github.mostavassoli.streamjack.core.evidence.ObservedRecord;
import io.github.mostavassoli.streamjack.core.evidence.ToolCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceWriterTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void writesRecordsAndToolCallsUnderTheScenarioId(@TempDir Path directory) throws Exception {
        var arguments = json.createObjectNode().put("ticketId", "T-1");
        var evidence = new Evidence(
                List.of(new ObservedRecord("support.triage", "T-1", Map.of("streamjack-event-id", "e1"),
                        "{\"recommendedAction\":\"PUBLISH\"}")),
                List.of(new ToolCall("createFollowUp", arguments, "e1")));
        var execution = new ScenarioExecution("run-1", Duration.ofMillis(42), evidence,
                ScenarioResult.inconclusive("SJ-101", "Example", "test"));

        Path file = new EvidenceWriter().write(execution, directory);

        assertThat(file.getFileName().toString()).isEqualTo("SJ-101.json");
        assertThat(directory.resolve("SJ-101.json.tmp")).doesNotExist();
        JsonNode root = json.readTree(Files.readString(file));
        assertThat(root.path("runId").asText()).isEqualTo("run-1");
        assertThat(root.path("execution").asText()).isEqualTo("INCONCLUSIVE");
        assertThat(root.path("outputRecords").get(0).path("value").asText()).contains("PUBLISH");
        assertThat(root.path("outputRecords").get(0).path("headers").path("streamjack-event-id").asText())
                .isEqualTo("e1");
        assertThat(root.path("toolCalls").get(0).path("arguments").path("ticketId").asText()).isEqualTo("T-1");
    }
}
