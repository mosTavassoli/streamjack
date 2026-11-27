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

    /** Upper bound for attempts per trial and invocation; only setup failures, which send nothing, use more than one. */
    public static final int MAX_ATTEMPTS = 10;

    /**
     * @param repository the repository root; scenario paths are stored relative to it, so a moved checkout or another
     *                   OS resumes the same plan
     */
    public record Options(
            BenchSettings settings,
            Path out,
            Path repository,
            List<Path> scenarioFiles,
            double costLimit,
            int maxAttempts,
            int maxTrials) {

        public Options {
            Objects.requireNonNull(settings, "settings");
            out = out.toAbsolutePath().normalize();
            repository = repository.toAbsolutePath().normalize();
            scenarioFiles = List.copyOf(scenarioFiles);
            if (out.getParent() == null || out.getParent().getParent() == null
                    || !Double.isFinite(costLimit) || costLimit <= 0 || maxAttempts < 1
                    || maxAttempts > MAX_ATTEMPTS || maxTrials < 0) {
                throw new IllegalArgumentException("Use a run directory under a dedicated spend directory, "
                        + "a finite positive cost limit, 1 to " + MAX_ATTEMPTS + " attempts and a nonnegative trial limit.");
            }
        }
    }

    private final Options options;
    private final TrialExecutor executor;
    private final PrintWriter log;

    public BenchRun(Options options, TrialExecutor executor, PrintWriter log) {
        this.options = options;
        this.executor = executor;
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
            // A halt anywhere under the spend directory blocks every run that shares its budget: the halted attempt's
            // cost may be misstated (for example, tokens of an unexpected model priced at the expected model's rates).
            String halted = haltedTrial(spendRoot);
            if (halted == null) {
                halted = unfinishedSibling(spendRoot, options.out());
            }
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
                    if (last != null && !last.outcome().retryable()) {
                        // The harness never dispatches after a final outcome, so this history is inconsistent: a later
                        // attempt must not hide the earlier result (for example an unknown or billed attempt).
                        return stop(trialDir, trial, lastNumber, attempt.getFileName() + " follows a final outcome ("
                                + last.outcome().wire() + ") of attempt " + lastNumber, last);
                    }
                    Path statusFile = attempt.resolve(STATUS);
                    if (!Files.exists(statusFile)) {
                        // Interrupted while running: it may have been billed, so it is final.
                        StateFiles.write(statusFile, AttemptStatus.of(Outcome.UNKNOWN, "interrupted", trial.events()));
                    }
                    // A recovered attempt is checked against its usage log exactly like a fresh one.
                    last = AttemptClassifier.reconcile(trial, StateFiles.read(statusFile, AttemptStatus.class),
                            Usage.read(attempt));
                    lastNumber = number(attempt);
                    String problem = Usage.problem(options.settings().model(), trial, last, Usage.read(attempt));
                    if (problem != null) {
                        return stop(trialDir, trial, lastNumber, problem, last);
                    }
                }
                AttemptStatus decided = null;
                if (last != null && !last.outcome().retryable()) {
                    decided = last.asFinal(lastNumber);
                }
                if (decided == null && options.maxTrials() > 0 && ran >= options.maxTrials()) {
                    log.println("Stopping after " + options.maxTrials() + " trial(s) as requested. Run again to continue.");
                    break;
                }
                // The attempt limit applies per invocation: only setup failures (which sent nothing) accumulate across
                // invocations, so a resumed run may try a trial again after, for example, Docker was restarted.
                int lastAllowed = attempts.size() + options.maxAttempts();
                for (int n = attempts.size() + 1; decided == null && n <= lastAllowed; n++) {
                    double spend = Spend.total(spendRoot);
                    if (spend + options.settings().trialCostReserve() > options.costLimit()) {
                        log.printf(Locale.ROOT, "Stopping: spend so far $%.4f plus the trial reserve would exceed the "
                                + "limit of $%.2f. Raise the limit and run again to resume.%n", spend, options.costLimit());
                        return COST_LIMIT;
                    }
                    Path attemptDir = trialDir.resolve(ATTEMPT_PREFIX + n);
                    createAttempt(attemptDir);
                    AttemptStatus status;
                    try {
                        status = executor.execute(trial, attemptDir);
                    } catch (RuntimeException e) {
                        // The executor may have sent requests before failing, so the outcome is unknown, not retryable.
                        status = AttemptStatus.of(Outcome.UNKNOWN, "executor error: " + e.getClass().getSimpleName(),
                                trial.events());
                    }
                    // Saved before the usage log is read, so a stop (with its process id) survives any failure there.
                    StateFiles.write(attemptDir.resolve(STATUS), status);
                    status = AttemptClassifier.reconcile(trial, status, Usage.read(attemptDir));
                    StateFiles.write(attemptDir.resolve(STATUS), status);
                    log.println(trial.id() + " attempt " + n + ": " + status.outcome().wire()
                            + (status.reason().isEmpty() ? "" : " (" + status.reason() + ")"));
                    String problem = Usage.problem(options.settings().model(), trial, status, Usage.read(attemptDir));
                    if (problem != null) {
                        return stop(trialDir, trial, n, problem, status);
                    }
                    last = status;
                    lastNumber = n;
                    if (!status.outcome().retryable()) {
                        decided = status.asFinal(n);
                    }
                }
                if (decided == null) {
                    // Every attempt failed before the scenario started (for example, Docker stopped while the computer
                    // slept). Nothing was sent, so the trial is left open instead of recorded as failed: continuing
                    // would only fail every remaining trial the same way.
                    log.println("Stopping: " + trial.id() + " could not be set up in " + options.maxAttempts()
                            + " attempt(s) (" + last.reason() + "). No request was sent. Check Docker, then run the "
                            + "same command again to resume.");
                    return STOPPED;
                }
                StateFiles.write(trialDir.resolve(FINAL), decided);
                ran++;
                if (decided.outcome() != Outcome.COMPLETED) {
                    unsuccessful++;
                }
                if (decided.outcome().halting() && !acknowledged(trialDir)) {
                    log.println("Stopping the run: " + trial.id() + " ended as " + decided.outcome().wire() + " ("
                            + decided.reason() + "). " + acknowledgeHint(trialDir));
                    return decided.outcome() == Outcome.STOPPED ? STOPPED : PROVIDER_ERROR;
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
        Manifest planned = Manifest.plan(options.repository(), options.scenarioFiles(), options.settings().modes(),
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

    private int stop(Path trialDir, Trial trial, int attempt, String usageProblem, AttemptStatus status) {
        // Keep an earlier stop's reason (for example the process id of an agent that did not exit).
        String problem = status.outcome() == Outcome.STOPPED ? usageProblem + "; " + status.reason() : usageProblem;
        StateFiles.write(trialDir.resolve(FINAL),
                new AttemptStatus(Outcome.STOPPED, problem, trial.events(), attempt, null, null));
        log.println("Stopping the run: " + problem + ". " + acknowledgeHint(trialDir));
        return STOPPED;
    }

    /**
     * The first unacknowledged halted trial in any run under the spend directory, described for the user, or null.
     * A halt is never cleared by replaying the trial; a person acknowledges it after inspecting the attempt, and the
     * run continues with the next trial while the halted one stays recorded as unsuccessful.
     */
    private static String haltedTrial(Path spendRoot) {
        for (Path finalFile : finalsUnder(spendRoot)) {
            AttemptStatus state = StateFiles.read(finalFile, AttemptStatus.class);
            if (state.outcome().halting() && !acknowledged(finalFile.getParent())) {
                return finalFile.getParent() + " ended as " + state.outcome().wire() + " (" + state.reason() + "). "
                        + acknowledgeHint(finalFile.getParent());
            }
        }
        return null;
    }

    /**
     * Another run under the same spend directory with a trial that has attempts but no final result, or null. Its last
     * attempt may have logged a provider error or an unexpected model before it was interrupted, so its cost and
     * outcome are not settled until that run is resumed (which reconciles it).
     */
    private static String unfinishedSibling(Path spendRoot, Path current) {
        try (Stream<Path> runs = Files.list(spendRoot)) {
            for (Path run : runs.filter(Files::isDirectory).filter(run -> !run.equals(current)).sorted().toList()) {
                Path trials = run.resolve(TRIALS);
                if (!Files.isDirectory(trials)) {
                    continue;
                }
                try (Stream<Path> trialDirs = Files.list(trials)) {
                    for (Path trial : trialDirs.filter(Files::isDirectory).sorted().toList()) {
                        if (!Files.exists(trial.resolve(FINAL)) && !attempts(trial).isEmpty()) {
                            return trial + " has attempts but no final result. Resume " + run
                                    + " first, so its outcome and cost are settled.";
                        }
                    }
                }
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static final String ACKNOWLEDGED = "acknowledged.json";

    /** Records that a person inspected a halted trial; the run then continues without replaying it. */
    public static void acknowledge(Path trialDir, String note) {
        Path finalFile = trialDir.resolve(FINAL);
        if (!Files.exists(finalFile) || !StateFiles.read(finalFile, AttemptStatus.class).outcome().halting()) {
            throw new IllegalArgumentException("Only a halted trial can be acknowledged: " + trialDir);
        }
        StateFiles.write(trialDir.resolve(ACKNOWLEDGED),
                Map.of("acknowledged", java.time.Instant.now().toString(), "note", note == null ? "" : note));
    }

    private static boolean acknowledged(Path trialDir) {
        return Files.exists(trialDir.resolve(ACKNOWLEDGED));
    }

    private static String acknowledgeHint(Path trialDir) {
        return "Inspect " + trialDir + ", reconcile the provider's usage, fix the cause, then run "
                + "'streamjack-bench acknowledge --trial " + trialDir + "' to continue with the next trial.";
    }

    private static List<Path> finalsUnder(Path spendRoot) {
        if (!Files.isDirectory(spendRoot)) {
            return List.of();
        }
        try (Stream<Path> runs = Files.list(spendRoot)) {
            return runs.filter(Files::isDirectory).sorted()
                    .flatMap(run -> finals(run.resolve(TRIALS)).stream()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
