package io.github.mostavassoli.streamjack.kafka.github;

public record CheckRunOutput(boolean failed, String title, String summary, String text) {

    public String conclusion() {
        return failed ? "failure" : "success";
    }
}
