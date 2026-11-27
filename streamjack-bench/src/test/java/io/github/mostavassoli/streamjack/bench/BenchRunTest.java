package io.github.mostavassoli.streamjack.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchRunTest {

    private static final String MODEL = "mock-model";
    private static final Path REPOSITORY = Path.of("..");
    private static final Path SCENARIO = REPOSITORY.resolve("experiments/defense-levels-v2/scenarios/sj-101.yaml");

    @TempDir
    Path work;

    @Test
    void recoversBothCrashPointsWithoutReplayingRequests() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BenchRun.Options options = options("run", 1, 2);
        assertThat(run(options, successful(calls, MODEL, true))).isZero();
        Path trial = firstTrial(options);
        Files.delete(trial.resolve(BenchRun.FINAL));
        assertThat(run(options, rejectDispatch())).isZero();
        assertThat(finalStatus(trial).outcome()).isEqualTo(Outcome.COMPLETED);

        Files.delete(trial.resolve(BenchRun.FINAL));
        Files.delete(trial.resolve("attempt-1").resolve(BenchRun.STATUS));
        assertThat(run(options, rejectDispatch())).isEqualTo(BenchRun.INCOMPLETE);
        assertThat(finalStatus(trial).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(run(options, rejectDispatch())).isEqualTo(BenchRun.INCOMPLETE);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(BenchRun.attempts(trial)).hasSize(1);
        assertThat(Spend.total(work)).isGreaterThan(options.settings().trialCostReserve());
    }

    @Test
    void wrongModelStopsBothLiveAndRecoveredAttempts() throws Exception {
        BenchRun.Options options = options("run", 2, 2);
        assertThat(run(options, successful(new AtomicInteger(), MODEL + "-unexpected", true))).isEqualTo(BenchRun.STOPPED);
        Path trial = firstTrial(options);
        assertThat(finalStatus(trial).outcome()).isEqualTo(Outcome.STOPPED);
        assertThat(run(options, rejectDispatch())).isEqualTo(BenchRun.STOPPED);

        Files.delete(trial.resolve(BenchRun.FINAL));
        assertThat(run(options, rejectDispatch())).isEqualTo(BenchRun.STOPPED);
        assertThat(finalStatus(trial).outcome()).isEqualTo(Outcome.STOPPED);
    }

    @Test
    void missingUsageStopsAndKeepsTheReserve() {
        BenchRun.Options options = options("run", 1, 1);
        assertThat(run(options, successful(new AtomicInteger(), MODEL, false))).isEqualTo(BenchRun.STOPPED);
        assertThat(Spend.total(work)).isEqualTo(options.settings().trialCostReserve());
    }

    @Test
    void budgetCountsPriorRunsWithTheirOwnPricesAndBlocksAnotherDispatch() {
        BenchRun.Options first = options("first", 1, 1);
        assertThat(run(first, successful(new AtomicInteger(), MODEL, true))).isZero();
        assertThat(Spend.total(work)).isCloseTo(0.001, org.assertj.core.data.Offset.offset(1e-10));
        BenchRun.Options second = options("second", 1, 1);
        BenchRun.Options limited = new BenchRun.Options(second.settings(), second.out(), REPOSITORY,
                second.scenarioFiles(), second.settings().trialCostReserve() + 0.0005, 1, 0);
        assertThat(run(limited, rejectDispatch())).isEqualTo(BenchRun.COST_LIMIT);
    }

    @Test
    void runSharingTheSpendDirectoryCannotDispatchConcurrently() {
        BenchRun.Options first = options("first", 1, 1);
        BenchRun.Options second = options("second", 1, 1);
        assertThat(run(first, (trial, dir) -> {
            assertThatThrownBy(() -> run(second, rejectDispatch()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Another benchmark");
            return successful(new AtomicInteger(), MODEL, true).execute(trial, dir);
        })).isZero();
    }

    @Test
    void refusesChangedPlanAndCorruptFinalState() throws Exception {
        BenchRun.Options options = options("run", 1, 1);
        assertThat(run(options, successful(new AtomicInteger(), MODEL, true))).isZero();
        assertThatThrownBy(() -> run(options("run", 2, 1), rejectDispatch())).hasMessageContaining("different replicates");
        Files.writeString(firstTrial(options).resolve(BenchRun.FINAL), "{\"outcome\":\"completed\"} garbage");
        assertThatThrownBy(() -> run(options, rejectDispatch())).hasMessageContaining("Unreadable state file");
    }

    @Test
    void rejectedModelOutputIsNotRetried() {
        BenchRun.Options options = options("run", 1, 3);
        AtomicInteger calls = new AtomicInteger();
        assertThat(run(options, (trial, dir) -> {
            successful(calls, MODEL, true).execute(trial, dir);
            return AttemptStatus.of(Outcome.MODEL_OUTPUT_INVALID, "invalid", trial.events());
        })).isEqualTo(BenchRun.INCOMPLETE);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(finalStatus(firstTrial(options)).outcome()).isEqualTo(Outcome.MODEL_OUTPUT_INVALID);
    }

    @Test
    void rejectsNonFiniteMoneySettingsAndNegativeTrialLimit() {
        BenchRun.Options options = options("run", 1, 1);
        assertThatThrownBy(() -> new BenchRun.Options(options.settings(), options.out(), REPOSITORY,
                options.scenarioFiles(), Double.NaN, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenchRun.Options(options.settings(), options.out(), REPOSITORY,
                options.scenarioFiles(), 1, 1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenchSettings(MODEL, "mock", 1, List.of("NEUTRAL"), 42,
                Double.POSITIVE_INFINITY, 1, 0.12, List.of("sj-101"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mockAgentUsesDummyKeyAndRedactsRealKey() {
        String syntheticKey = "synthetic-secret-for-tests";
        var mock = new DockerTrialExecutor.Agent(work, MODEL, "http://127.0.0.1:18080/v1/", syntheticKey);
        assertThat(mock.apiKey()).isEqualTo(Setup.MOCK_KEY);
        var real = new DockerTrialExecutor.Agent(work, MODEL, null, syntheticKey);
        assertThat(real.toString()).doesNotContain(syntheticKey);
        // An allowlist: configuration under any other name, including Quarkus profile variables, is not inherited.
        assertThat(DockerTrialExecutor.inherited("PATH")).isTrue();
        assertThat(DockerTrialExecutor.inherited("SystemRoot")).isTrue();
        assertThat(DockerTrialExecutor.inherited("_PROD_QUARKUS_LANGCHAIN4J_OPENAI_BASE_URL")).isFalse();
        assertThat(DockerTrialExecutor.inherited("qUaRkUs_Profile")).isFalse();
        assertThat(DockerTrialExecutor.inherited("OPENAI_API_KEY")).isFalse();
        assertThat(DockerTrialExecutor.inherited("JDK_JAVA_OPTIONS")).isFalse();
    }

    @Test
    void partialRateLimitIsNotRetriedAndProviderErrorsAreNeverLost() throws Exception {
        // One of two calls rate-limited, the other unaccounted for: it may have been processed, so no replay.
        BenchRun.Options options = options("partial", 1, 3);
        AtomicInteger dispatches = new AtomicInteger();
        assertThat(run(options, (trial, dir) -> {
            dispatches.incrementAndGet();
            write(dir, "{\"outcome\":\"error\",\"error\":\"dev.langchain4j.exception.RateLimitException\"}\n");
            return AttemptStatus.of(Outcome.RATE_LIMITED, "provider rate limit", trial.events());
        })).isEqualTo(BenchRun.INCOMPLETE);
        assertThat(dispatches.get()).isEqualTo(1);
        assertThat(finalStatus(firstTrial(options)).outcome()).isEqualTo(Outcome.UNKNOWN);

        // An executor that logs a provider error and then throws: the error still halts the run.
        BenchRun.Options thrown = options("thrown", 1, 3);
        assertThat(run(thrown, (trial, dir) -> {
            write(dir, "{\"outcome\":\"error\",\"error\":\"dev.langchain4j.exception.AuthenticationException\"}\n");
            throw new IllegalStateException("report write failed");
        })).isEqualTo(BenchRun.PROVIDER_ERROR);
        assertThat(finalStatus(firstTrial(thrown)).outcome()).isEqualTo(Outcome.PROVIDER_ERROR);
    }

    @Test
    void aFullRateLimitHaltsAndIsNeverRetried() {
        BenchRun.Options options = options("limited", 1, 3);
        AtomicInteger dispatches = new AtomicInteger();
        String limit = "{\"outcome\":\"error\",\"error\":\"dev.langchain4j.exception.RateLimitException\"}\n";
        assertThat(run(options, (trial, dir) -> {
            dispatches.incrementAndGet();
            write(dir, limit.repeat(trial.events()));
            return AttemptStatus.of(Outcome.RATE_LIMITED, "provider rate limit", trial.events());
        })).isEqualTo(BenchRun.PROVIDER_ERROR);
        assertThat(dispatches.get()).isEqualTo(1);
        assertThat(finalStatus(firstTrial(options)).outcome()).isEqualTo(Outcome.RATE_LIMITED);
        assertThat(run(options, rejectDispatch())).isEqualTo(BenchRun.STOPPED);
    }

    @Test
    void anUnfinishedSiblingRunBlocksDispatchUntilItIsResumed() throws Exception {
        BenchRun.Options crashed = options("crashed", 1, 1);
        assertThat(run(crashed, successful(new AtomicInteger(), MODEL, true))).isZero();
        // Simulate a crash after the attempt but before the trial's final result was written.
        Files.delete(firstTrial(crashed).resolve(BenchRun.FINAL));

        BenchRun.Options sibling = options("sibling", 1, 1);
        assertThat(run(sibling, rejectDispatch())).isEqualTo(BenchRun.STOPPED);
        // Resuming the crashed run settles it; then the sibling may dispatch.
        assertThat(run(crashed, rejectDispatch())).isZero();
        assertThat(run(sibling, successful(new AtomicInteger(), MODEL, true))).isZero();
    }

    @Test
    void aHaltInOneRunBlocksEverySiblingRunUntilAcknowledged() {
        BenchRun.Options wrong = options("wrong", 1, 1);
        assertThat(run(wrong, successful(new AtomicInteger(), "some-other-model", true))).isEqualTo(BenchRun.STOPPED);
        // The other model's tokens cannot be priced at this run's prices, so the attempt counts at the reserve.
        assertThat(Spend.total(work)).isGreaterThanOrEqualTo(wrong.settings().trialCostReserve());

        BenchRun.Options sibling = options("sibling", 1, 1);
        assertThat(run(sibling, rejectDispatch())).isEqualTo(BenchRun.STOPPED);

        // Acknowledging does not replay the halted trial; the sibling may then dispatch.
        BenchRun.acknowledge(firstTrial(wrong), "checked provider usage");
        assertThat(run(wrong, rejectDispatch())).isEqualTo(BenchRun.INCOMPLETE);
        assertThat(run(sibling, successful(new AtomicInteger(), MODEL, true))).isZero();
        assertThatThrownBy(() -> BenchRun.acknowledge(firstTrial(sibling), "not halted"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manifestStoresRepositoryRelativePaths() {
        BenchRun.Options options = options("run", 1, 1);
        assertThat(run(options, successful(new AtomicInteger(), MODEL, true))).isZero();
        Trial trial = StateFiles.read(options.out().resolve(BenchRun.MANIFEST), Manifest.class).trials().getFirst();
        assertThat(trial.scenarioFile()).isEqualTo("experiments/defense-levels-v2/scenarios/sj-101.yaml");
    }

    private static void write(Path dir, String usage) {
        try {
            Files.writeString(dir.resolve(Usage.FILE), usage);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private BenchRun.Options options(String name, int replicates, int attempts) {
        BenchSettings settings = new BenchSettings(MODEL, "mock", replicates, List.of("NEUTRAL"), 42, 1, 2, 0.12,
                List.of("sj-101"), Map.of("test", "test"));
        return new BenchRun.Options(settings, work.resolve(name), REPOSITORY, List.of(SCENARIO), 10, attempts, 0);
    }

    private static int run(BenchRun.Options options, TrialExecutor executor) {
        return new BenchRun(options, executor, new PrintWriter(new StringWriter())).run();
    }

    private static TrialExecutor successful(AtomicInteger calls, String served, boolean writeUsage) {
        return (trial, dir) -> {
            calls.addAndGet(trial.events());
            if (writeUsage) {
                String line = "{\"outcome\":\"response\",\"model\":\"" + served
                        + "\",\"inputTokens\":300,\"outputTokens\":100}\n";
                try {
                    Files.writeString(dir.resolve(Usage.FILE), line.repeat(trial.events()));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            return AttemptStatus.of(Outcome.COMPLETED, "", trial.events());
        };
    }

    private static TrialExecutor rejectDispatch() {
        return (trial, dir) -> { throw new AssertionError("Unexpected dispatch for " + trial.id()); };
    }

    private static Path firstTrial(BenchRun.Options options) {
        Trial trial = StateFiles.read(options.out().resolve(BenchRun.MANIFEST), Manifest.class).trials().getFirst();
        return options.out().resolve(BenchRun.TRIALS).resolve(trial.id());
    }

    private static AttemptStatus finalStatus(Path trial) {
        return StateFiles.read(trial.resolve(BenchRun.FINAL), AttemptStatus.class);
    }
}
