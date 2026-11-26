package io.github.mostavassoli.streamjack.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Spend across every run directory under one root (pilots and retries included), so one limit covers the whole
 * experiment. It fails closed: a run without prices is an error, and an attempt whose cost the usage log may not show
 * (interrupted, failed, provider error, missing or unpriced lines) counts at that run's worst-case trial reserve.
 */
final class Spend {

    private Spend() {
    }

    static double total(Path spendRoot) {
        if (!Files.isDirectory(spendRoot)) {
            return 0;
        }
        double total = 0;
        try (Stream<Path> runs = Files.list(spendRoot)) {
            for (Path run : runs.filter(Files::isDirectory).sorted().toList()) {
                Path trials = run.resolve(BenchRun.TRIALS);
                if (!Files.isDirectory(trials)) {
                    continue;
                }
                Path settingsFile = run.resolve(BenchRun.SETTINGS);
                if (!Files.exists(settingsFile)) {
                    throw new IllegalStateException("Cannot price spend in " + run + ": no settings.json");
                }
                BenchSettings settings = StateFiles.read(settingsFile, BenchSettings.class);
                for (Path attempt : attempts(trials)) {
                    total += attemptCost(attempt, settings);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read spend under " + spendRoot, e);
        }
        return total;
    }

    static double attemptCost(Path attemptDir, BenchSettings settings) {
        Path statusFile = attemptDir.resolve(BenchRun.STATUS);
        AttemptStatus status = Files.exists(statusFile) ? StateFiles.read(statusFile, AttemptStatus.class) : null;
        List<Usage.Entry> entries = Usage.read(attemptDir);
        double cost = 0;
        int priced = 0;
        boolean unclear = status == null || status.outcome().costUnclear();
        for (Usage.Entry entry : entries) {
            if (entry.response() && !Usage.servedAs(settings.model(), entry.model())) {
                // Another model's tokens cannot be priced at this run's prices.
                unclear = true;
            }
            if (entry.priced()) {
                // Completion tokens include reasoning; prompt tokens include cached ones, priced at the full input
                // price, which overstates cost when the provider applies a cache discount.
                cost += (entry.inputTokens() * settings.inputPrice() + entry.outputTokens() * settings.outputPrice()) / 1e6;
                priced++;
            } else {
                unclear = true;
            }
        }
        // Fewer logged responses than tickets means a call the log does not account for, unless the attempt never got
        // as far as sending one (setup failed before the scenario started).
        if (status != null && status.outcome() != Outcome.SETUP_FAILED && priced < status.events()) {
            unclear = true;
        }
        return unclear ? cost + settings.trialCostReserve() : cost;
    }

    private static List<Path> attempts(Path trials) throws IOException {
        try (Stream<Path> paths = Files.walk(trials, 2)) {
            return paths.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(BenchRun.ATTEMPT_PREFIX))
                    .sorted()
                    .toList();
        }
    }
}
