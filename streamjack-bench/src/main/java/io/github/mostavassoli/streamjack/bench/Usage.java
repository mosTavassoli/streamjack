package io.github.mostavassoli.streamjack.bench;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The agent's usage log for one attempt (one JSON line per model call, written by the sample agent's UsageLog), and the
 * checks the harness applies to it.
 */
final class Usage {

    static final String FILE = "usage.jsonl";
    private static final Pattern MODEL_ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9.-]{0,127}");

    /** One model call: a response with token counts, or an error with its exception type. */
    record Entry(String outcome, String model, Integer inputTokens, Integer outputTokens, String finishReason,
            String error) {

        boolean response() {
            return "response".equals(outcome);
        }

        boolean priced() {
            return response() && inputTokens != null && outputTokens != null;
        }

        String displayModel() {
            return model != null && MODEL_ID.matcher(model).matches() ? model : null;
        }
    }

    private Usage() {
    }

    static List<Entry> read(Path attemptDir) {
        Path file = attemptDir.resolve(FILE);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<Entry> entries = new ArrayList<>();
            // Decoded leniently: a line cut inside a multi-byte character becomes an unreadable line, not a failure.
            String content = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : content.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = StateFiles.JSON.readTree(line);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    // For example a line cut off when the agent was killed: an unaccounted call, which halts the run.
                    entries.add(new Entry("error", null, null, null, null, "unreadable usage line"));
                    continue;
                }
                entries.add(new Entry(text(node, "outcome"), text(node, "model"), number(node, "inputTokens"),
                        number(node, "outputTokens"), text(node, "finishReason"), text(node, "error")));
            }
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable usage log " + file, e);
        }
    }

    /**
     * Returns why the run must stop, or null. A model call the log cannot account for, or a different served model,
     * means neither the results nor the spend can be trusted.
     */
    /** The exact alias, or the alias plus a dated snapshot suffix (for example gpt-6-luna-2026-10-01). */
    static boolean servedAs(String requested, String served) {
        return served != null && Pattern.matches(Pattern.quote(requested) + "(-\\d{4}-\\d{2}-\\d{2})?", served);
    }

    static String problem(String model, Trial trial, AttemptStatus status, List<Entry> entries) {
        List<Entry> responses = entries.stream().filter(Entry::response).toList();
        for (Entry response : responses) {
            if (!servedAs(model, response.model())) {
                return "served model does not match '" + model + "'";
            }
            if (!response.priced()) {
                return "a response has no token counts";
            }
        }
        if (status.outcome() == Outcome.COMPLETED && responses.size() != trial.events()) {
            return "expected " + trial.events() + " model calls, the usage log has " + responses.size();
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0
                ? null : value.intValue();
    }
}
