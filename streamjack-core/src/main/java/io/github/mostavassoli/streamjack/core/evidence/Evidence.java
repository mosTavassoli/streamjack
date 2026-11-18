package io.github.mostavassoli.streamjack.core.evidence;

import java.util.List;

public record Evidence(List<ObservedRecord> outputRecords, List<ToolCall> toolCalls) {

    public Evidence {
        outputRecords = outputRecords == null ? List.of() : List.copyOf(outputRecords);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
