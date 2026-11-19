package io.github.mostavassoli.streamjack.core.report;

import java.util.List;

public class ReportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> problems;

    public ReportException(String source, List<String> problems) {
        super("Invalid report " + source + ":\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public ReportException(String source, Throwable cause) {
        super("Cannot read report " + source + ": " + cause.getMessage(), cause);
        this.problems = List.of(String.valueOf(cause.getMessage()));
    }

    public List<String> problems() {
        return problems;
    }
}
