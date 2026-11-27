package io.github.mostavassoli.streamjack.bench.cli;

import io.github.mostavassoli.streamjack.bench.Analyzer;
import io.github.mostavassoli.streamjack.bench.BenchRun;
import io.github.mostavassoli.streamjack.bench.BenchSettings;
import io.github.mostavassoli.streamjack.bench.DockerTrialExecutor;
import io.github.mostavassoli.streamjack.bench.MockOpenAiServer;
import io.github.mostavassoli.streamjack.bench.Scorer;
import io.github.mostavassoli.streamjack.bench.Setup;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Command line of the benchmark harness. Run from the repository root; see docs/benchmark-v2-plan.md. */
@Command(name = "streamjack-bench", mixinStandardHelpOptions = true,
        subcommands = {Bench.Run.class, Bench.Acknowledge.class, Bench.Score.class, Bench.Analyze.class,
                Bench.MockOpenAi.class},
        description = "Real-model defense-levels benchmark harness.")
public final class Bench {

    public static void main(String[] args) {
        System.exit(new CommandLine(new Bench()).execute(args));
    }

    @Command(name = "run", description = "Run (or resume) a benchmark for one model.")
    static final class Run implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Option(names = "--model", required = true, description = "OpenAI model name, for example gpt-6-luna")
        String model;

        @Option(names = "--agent-dir", required = true,
                description = "The sample agent's quarkus-app directory, built for the OpenAI provider")
        Path agentDir;

        @Option(names = "--out", required = true, description = "Run directory; its parent holds all runs that share the cost limit")
        Path out;

        @Option(names = "--replicates", defaultValue = "10", description = "Replicates per mode and scenario (default: ${DEFAULT-VALUE})")
        int replicates;

        @Option(names = "--modes", split = ",", defaultValue = "NEUTRAL,PROMPT_ONLY,HARDENED",
                description = "Security modes (default: ${DEFAULT-VALUE})")
        List<String> modes;

        @Option(names = "--scenario", description = "Run only these scenarios (file names without .yaml); repeatable")
        List<String> scenarioFilter = new ArrayList<>();

        @Option(names = "--input-price", required = true, description = "US dollars per million input tokens")
        double inputPrice;

        @Option(names = "--output-price", required = true, description = "US dollars per million output tokens")
        double outputPrice;

        @Option(names = "--cost-limit", required = true,
                description = "Total spend allowed across every run directory under the parent of --out")
        double costLimit;

        @Option(names = "--trial-cost-reserve", defaultValue = "0.12",
                description = "Worst-case cost of one trial (default: ${DEFAULT-VALUE}, enough for gpt-6.2-sol)")
        double trialCostReserve;

        @Option(names = "--max-attempts", defaultValue = "3", description = "Attempts per trial (default: ${DEFAULT-VALUE})")
        int maxAttempts;

        @Option(names = "--max-trials", defaultValue = "0", description = "Stop after this many trials (0 = no limit)")
        int maxTrials;

        @Option(names = "--seed", defaultValue = "20261126", description = "Shuffle seed (default: ${DEFAULT-VALUE})")
        long seed;

        @Option(names = "--openai-base-url", description = "A local mock endpoint (loopback only) instead of the real API")
        String baseUrl;

        @Option(names = "--api-key-file", description = "File outside the repository containing only the API key "
                + "(default: .streamjack/openai-api-key.txt in your home directory); OPENAI_API_KEY takes precedence")
        Path apiKeyFile = Path.of(System.getProperty("user.home"), ".streamjack", "openai-api-key.txt");

        @Override
        public Integer call() throws IOException {
            PrintWriter log = spec.commandLine().getOut();
            try {
                if (maxAttempts < 1 || maxAttempts > BenchRun.MAX_ATTEMPTS) {
                    throw new IllegalArgumentException("--max-attempts must be between 1 and " + BenchRun.MAX_ATTEMPTS);
                }
                Path repository = Path.of("").toAbsolutePath();
                List<Path> scenarioFiles = Setup.scenarioFiles(repository, scenarioFilter);
                String apiKey = Setup.apiKey(baseUrl, System.getenv("OPENAI_API_KEY"), apiKeyFile, repository);
                BenchSettings settings = Setup.settings(model, baseUrl, replicates, modes, seed, inputPrice, outputPrice,
                        trialCostReserve, scenarioFiles, agentDir);
                BenchRun run = new BenchRun(
                        new BenchRun.Options(settings, out, repository, scenarioFiles, costLimit, maxAttempts, maxTrials),
                        new DockerTrialExecutor(new DockerTrialExecutor.Agent(agentDir, model, baseUrl, apiKey), repository),
                        log);
                return run.run();
            } catch (IllegalArgumentException | IllegalStateException e) {
                spec.commandLine().getErr().println("streamjack-bench: " + e.getMessage());
                return 2;
            }
        }
    }

    @Command(name = "acknowledge",
            description = "After inspecting a halted trial, let its run continue with the next trial (it is not replayed).")
    static final class Acknowledge implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Option(names = "--trial", required = true, description = "The halted trial's directory")
        Path trial;

        @Option(names = "--note", description = "What was inspected and why it is safe to continue")
        String note;

        @Override
        public Integer call() {
            try {
                BenchRun.acknowledge(trial, note);
                spec.commandLine().getOut().println("Acknowledged " + trial + "; the run continues with the next trial.");
                return 0;
            } catch (IllegalArgumentException e) {
                spec.commandLine().getErr().println("streamjack-bench: " + e.getMessage());
                return 2;
            }
        }
    }

    @Command(name = "score", description = "Score every trial of a run directory into scores.json and scores.csv.")
    static final class Score implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Option(names = "--run", required = true, description = "Run directory")
        Path run;

        @Override
        public Integer call() throws IOException {
            List<Scorer.Row> rows = Scorer.scoreRun(run);
            Setup.writeScores(run, rows);
            spec.commandLine().getOut().println("Scored " + rows.size() + " trials: " + run.resolve("scores.json"));
            return 0;
        }
    }

    @Command(name = "analyze", description = "Summarize scores.json files as Markdown tables.")
    static final class Analyze implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Option(names = "--scores", required = true, split = ",", description = "scores.json files of one or more runs")
        List<Path> scores;

        @Option(names = "--out", description = "Write the Markdown to this file as well")
        Path out;

        @Override
        public Integer call() throws IOException {
            String markdown = Analyzer.analyze(Setup.readScores(scores));
            spec.commandLine().getOut().print(markdown);
            if (out != null) {
                Files.writeString(out, markdown, StandardCharsets.UTF_8);
            }
            return 0;
        }
    }

    @Command(name = "mock-openai", description = "Serve a local mock of the OpenAI Chat Completions API until stopped.")
    static final class MockOpenAi implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Option(names = "--port", defaultValue = "18080", description = "Port on 127.0.0.1 (default: ${DEFAULT-VALUE})")
        int port;

        @Option(names = "--behavior", defaultValue = "normal",
                description = "normal, wrong-model, no-usage, server-error, or rate-limit (default: ${DEFAULT-VALUE})")
        String behavior;

        @Override
        public Integer call() throws Exception {
            try (MockOpenAiServer server = new MockOpenAiServer(port, MockOpenAiServer.Behavior.parse(behavior))) {
                spec.commandLine().getOut().println("Mock OpenAI (" + behavior + ") at " + server.baseUrl());
                spec.commandLine().getOut().flush();
                Thread.currentThread().join();
            }
            return 0;
        }
    }
}
