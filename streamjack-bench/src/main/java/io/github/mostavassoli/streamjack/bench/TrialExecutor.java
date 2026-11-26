package io.github.mostavassoli.streamjack.bench;

import java.nio.file.Path;

/**
 * Runs one attempt of a trial and reports how it ended. The real executor starts a broker and the agent; tests use a
 * fake one to drive every failure path without Docker or a model.
 *
 * <p>The executor writes the agent's usage log, the report, and the evidence into {@code attemptDir}. The harness writes
 * the returned status there afterwards.
 */
public interface TrialExecutor {

    AttemptStatus execute(Trial trial, Path attemptDir);
}
