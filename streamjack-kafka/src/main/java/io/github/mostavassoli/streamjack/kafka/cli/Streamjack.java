package io.github.mostavassoli.streamjack.kafka.cli;

import java.util.Map;
import picocli.CommandLine;

public final class Streamjack {

    static final String VERSION = "streamjack 0.3.0-SNAPSHOT";

    private Streamjack() {
    }

    public static void main(String[] args) {
        int exitCode = commandLine(System.getenv()).execute(args);
        System.exit(exitCode);
    }

    static CommandLine commandLine(Map<String, String> environment) {
        return new CommandLine(new StreamjackCommand())
                .addSubcommand(new GitHubCheckCommand(environment));
    }
}
