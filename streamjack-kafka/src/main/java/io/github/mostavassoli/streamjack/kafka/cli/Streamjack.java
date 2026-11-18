package io.github.mostavassoli.streamjack.kafka.cli;

import picocli.CommandLine;

public final class Streamjack {

    private Streamjack() {
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new StreamjackCommand()).execute(args);
        System.exit(exitCode);
    }
}
