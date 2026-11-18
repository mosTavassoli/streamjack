package io.github.mostavassoli.streamjack.core.scenario;

import java.util.List;

public class ScenarioException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> problems;

    public ScenarioException(String source, List<String> problems) {
        super("Invalid scenario " + source + ":\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public ScenarioException(String source, Throwable cause) {
        super("Cannot read scenario " + source + ": " + cause.getMessage(), cause);
        this.problems = List.of(cause.getMessage());
    }

    public List<String> problems() {
        return problems;
    }
}
