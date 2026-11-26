package io.github.mostavassoli.streamjack.bench;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Runs a benchmark for one model: frozen settings and manifest, one attempt at a time through a {@link TrialExecutor},
 * resumable from recorded state.
 *
 * <p>Resuming never repeats a completed or possibly billed attempt: an attempt without a status was interrupted and is
 * final as {@link Outcome#UNKNOWN}. Only {@link Outcome#retryable()} attempts are retried. A halting outcome stops the
 * run, and a halted run refuses to continue until a person has removed its halting final.json. Before each attempt the
 * spend across all runs under the parent directory is checked against the cost limit.
 */
public final class BenchRun {

    static final String SETTINGS = "settings.json";
    static final String MANIFEST = "manifest.json";
    static final String TRIALS = "trials";
    static final String STATUS = "status.json";
    static final String FINAL = "final.json";
    static final String ATTEMPT_PREFIX = "attempt-";

    /** Exit codes, also documented in docs/benchmark-v2-plan.md. */
    public static final int ALL_COMPLETED = 0;
    public static final int COST_LIMIT = 3;
    public static final int STOPPED = 4;
    public static final int PROVIDER_ERROR = 5;
    public static final int INCOMPLETE = 6;

    /** Sleeps between rate-limited attempts; replaceable in tests. */
    public interface Sleeper {
        void sleep(Duration duration);
    }

    public record Options(
            BenchSettings settings,
            Path out,
            List<Path> scenarioFiles,
            double costLimit,
            int maxAttempts,
            int maxTrials) {

        public Options {
            Objects.requireNonNull(settings, "settings");
            out = out.toAbsolutePath().normalize();
            scenarioFiles = List.copyOf(scenarioFiles);
            if (out.getParent() == null || out.getParent().getParent() == null
                    || !Double.isFinite(costLimit) || costLimit <= 0 || maxAttempts < 1 || maxTrials < 0) {
                throw new IllegalArgumentException("Use a run directory under a dedicated spend directory, "
                        + "a finite positive cost limit, at least one attempt and a nonnegative trial limit.");
            }
        }
    }

    private final Options options;
    private final TrialExecutor executor;
    private final Sleeper sleeper;
    private final PrintWriter log;

    public BenchRun(Options options, TrialExecutor executor, Sleeper sleeper, PrintWriter log) {
        this.options = options;
        this.executor = executor;
        this.sleeper = sleeper;
        this.log = log;
    }

    public int run() {
        Path spendRoot = options.out().getParent();
        createDirectories(spendRoot);
        // Serialize both the budget check and the attempt. A second process must not reserve the same funds.
        try (FileChannel channel = FileChannel.open(spendRoot.resolve(".streamjack-bench.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock()) {
            if (lock == null) {
                throw new IllegalStateException("Another benchmark is using " + spendRoot);
            }
            return runLocked(spendRoot);
        } catch (OverlappingFileLockException e) {
            throw new IllegalStateException("Another benchmark is using " + spendRoot, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot lock " + spendRoot, e);
        }
    }

    private int runLocked(Path spendRoot) {
        Manifest manifest = prepare();
        Path trialsDir = options.out().resolve(TRIALS);
        try {
            String halted = haltedTrial(trialsDir);
            if (halted != null) {
                log.println("Refusing to continue: " + halted);
                return STOPPED;
            }
            int ran = 0;
            int unsuccessful = 0;
            for (Trial trial : manifest.trials()) {
                Path trialDir = trialsDir.resolve(trial.id());
                if (Files.exists(trialDir.resolve(FINAL))) {
                    AttemptStatus state = StateFiles.read(trialDir.resolve(FINAL), AttemptStatus.class);
                    if (state.outcome() != Outcome.COMPLETED) {
                        unsuccessful++;
                    }
                    continue;
                }
                List<Path> attempts = attempts(trialDir);
                AttemptStatus last = null;
                int lastNumber = 0;
                for (Path attempt : attempts) {
                    Path statusFile = attempt.resolve(STATUS);
                    if (!Files.exists(statusFile)) {
                        // Interrupted while running: it may have been billed, so it is final.
                        StateFiles.write(statusFile, AttemptStatus.of(Outcome.UNKNOWN, "interrupted", trial.events()));
                    }
                    last = StateFiles.read(statusFile, AttemptStatus.class);
                    lastNumber = number(attempt);
                    if (last.outcome() == Outcome.COMPLETED) {
                        break;
                    }
                }
                AttemptStatus decided = null;
                if (last != null && !last.outcome().retryable()) {
                    // A recovered attempt gets the same usage check as a fresh one before it is accepted.
                    Path lastDir = trialDir.resolve(ATTEMPT_PREFIX + lastNumber);
                    String problem = Usage.problem(options.settings().model(), trial, last, Usage.read(lastDir));
                    if (problem != null) {
                        return stop(trialDir, trial, lastNumber, problem);
                    }
                    decided = last.asFinal(lastNumber);
                }
                if (decided == null && options.maxTrials() > 0 && ran >= options.maxTrials()) {
                    log.println("Stopping after " + options.maxTrials() + " trial(s) as requested. Run again to continue.");
                    break;
                }
                for (int n = attempts.size() + 1; decided == null && n <= options.maxAttempts(); n++) {
                    double spend = Spend.total(spendRoot);
                    if (spend + options.settings().trialCostReserve() > options.costLimit()) {
                        log.printf(Locale.ROOT, "Stopping: spend so far $%.4f plus the trial reserve would exceed the "
                                + "limit of $%.2f. Raise the limit and run again to resume.%n", spend, options.costLimit());
                        return COST_LIMIT;
                    }
                    if (last != null && last.outcome() == Outcome.RATE_LIMITED) {
                        sleeper.sleep(Duration.ofSeconds(30L * (n - 1)));
                    }
                    Path attemptDir = trialDir.resolve(ATTEMPT_PREFIX + n);
                    createAttempt(attemptDir);
                    AttemptStatus status = executor.execute(trial, attemptDir);
                    StateFiles.write(attemptDir.resolve(STATUS), status);
                    log.println(trial.id() + " attempt " + n + ": " + status.outcome().wire()
                            + (status.reason().isEmpty() ? "" : " (" + status.reason() + ")"));
                    String problem = Usage.problem(options.settings().model(), trial, status, Usage.read(attemptDir));
                    if (problem != null) {
                        return stop(trialDir, trial, n, problem);
                    }
                    last = status;
                    lastNumber = n;
                    if (!status.outcome().retryable()) {
                        decided = status.asFinal(n);
                    }
                }
                if (decided == null) {
                    decided = last.asFinal(lastNumber);
                }
                StateFiles.write(trialDir.resolve(FINAL), decided);
                ran++;
                if (decided.outcome() != Outcome.COMPLETED) {
                    unsuccessful++;
                }
                if (decided.outcome().halting()) {
                    log.println("Stopping the run: " + trial.id() + " ended as " + decided.outcome().wire() + " ("
                            + decided.reason() + "). Inspect it before resuming.");
                    return PROVIDER_ERROR;
                }
            }
            // A prior unsuccessful trial must not turn into a successful smoke test on resume.
            return unsuccessful > 0 ? INCOMPLETE : ALL_COMPLETED;
        } finally {
            summarize(trialsDir, manifest, spendRoot);
        }
    }

    private Manifest prepare() {
        Path settingsFile = options.out().resolve(SETTINGS);
        Path manifestFile = options.out().resolve(MANIFEST);
        Manifest planned = Manifest.plan(options.scenarioFiles(), options.settings().modes(),
                options.settings().replicates(), options.settings().seed());
        if (Files.exists(manifestFile)) {
            BenchSettings saved = StateFiles.read(settingsFile, BenchSettings.class);
            List<String> different = options.settings().differences(saved);
            if (!different.isEmpty()) {
                throw new IllegalStateException("This run was started with different " + String.join(", ", different)
                        + "; use a new output directory.");
            }
            Manifest savedManifest = StateFiles.read(manifestFile, Manifest.class);
            if (!savedManifest.equals(planned)) {
                throw new IllegalStateException("Saved manifest does not match this plan; use a new output directory.");
            }
            return planned;
        }
        if (Files.exists(options.out().resolve(TRIALS))) {
            throw new IllegalStateException("Cannot resume trials without their manifest.");
        }
        StateFiles.write(settingsFile, options.settings());
        StateFiles.write(manifestFile, planned);
        log.println("Planned " + planned.trials().size() + " trials in " + options.out());
        return planned;
    }

    private int stop(Path trialDir, Trial trial, int attempt, String problem) {
        StateFiles.write(trialDir.resolve(FINAL),
                new AttemptStatus(Outcome.STOPPED, problem, trial.events(), attempt, null, null));
        log.println("Stopping the run: " + problem + ". Inspect " + trialDir.resolve(ATTEMPT_PREFIX + attempt) + ".");
        return STOPPED;
    }

    /** The first trial that ended with a halting outcome, described for the user, or null. */
    private static String haltedTrial(Path trialsDir) {
        for (Path finalFile : finals(trialsDir)) {
            AttemptStatus state = StateFiles.read(finalFile, AttemptStatus.class);
            if (state.outcome().halting()) {
                return finalFile.getParent().getFileName() + " ended as " + state.outcome().wire() + " ("
                        + state.reason() + "). Inspect it, fix the cause, and delete " + finalFile + " to resume.";
            }
        }
        return null;
    }

    private void summarize(Path trialsDir, Manifest manifest, Path spendRoot) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Path finalFile : finals(trialsDir)) {
            counts.merge(StateFiles.read(finalFile, AttemptStatus.class).outcome().wire(), 1, Integer::sum);
        }
        int finished = counts.values().stream().mapToInt(Integer::intValue).sum();
        log.printf(Locale.ROOT, "Trials finished: %d of %d %s. Spend across %s: $%.4f%n", finished,
                manifest.trials().size(), counts, spendRoot, Spend.total(spendRoot));
        log.flush();
    }

    private static List<Path> finals(Path trialsDir) {
        if (!Files.isDirectory(trialsDir)) {
            return List.of();
        }
        try (Stream<Path> trials = Files.list(trialsDir)) {
            return trials.map(trial -> trial.resolve(FINAL)).filter(Files::exists).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Path> attempts(Path trialDir) {
        if (!Files.isDirectory(trialDir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(trialDir)) {
            List<Path> attempts = new ArrayList<>(paths.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(ATTEMPT_PREFIX))
                    .toList());
            attempts.sort(Comparator.comparingInt(BenchRun::number));
            return attempts;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int number(Path attempt) {
        return Integer.parseInt(attempt.getFileName().toString().substring(ATTEMPT_PREFIX.length()));
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void createAttempt(Path directory) {
        try {
            Files.createDirectories(directory.getParent());
            Files.createDirectory(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot reserve attempt " + directory, e);
        }
    }
}
