package io.github.mostavassoli.streamjack.bench;

import java.util.Objects;

/**
 * The recorded result of one attempt (attempt-N/status.json) or of a whole trial (final.json, where {@code attempt}
 * names the attempt it was decided by).
 *
 * @param events number of tickets the trial sends, which is the number of model calls a complete attempt makes
 */
public record AttemptStatus(Outcome outcome, String reason, int events, int attempt, String started, String finished) {

    public AttemptStatus {
        Objects.requireNonNull(outcome, "outcome");
        reason = reason == null ? "" : reason;
    }

    static AttemptStatus of(Outcome outcome, String reason, int events) {
        return new AttemptStatus(outcome, reason, events, 0, null, null);
    }

    AttemptStatus asFinal(int attempt) {
        return new AttemptStatus(outcome, reason, events, attempt, started, finished);
    }
}
