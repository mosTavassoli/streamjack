package io.github.mostavassoli.streamjack.bench;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** How an attempt or a trial ended. */
public enum Outcome {
    /** The scenario ran to the end and the evidence was written. */
    COMPLETED,
    /** The agent rejected the model's output; a result, not retried. */
    MODEL_OUTPUT_INVALID,
    /** The provider rejected or failed a request (key, model access, request format, server error); halts the run. */
    PROVIDER_ERROR,
    /** The provider rate-limited every request and returned no paid response; retried with backoff. */
    RATE_LIMITED,
    /** Broker or agent did not start, so no model request was sent; retried. */
    SETUP_FAILED,
    /** The scenario did not complete for another reason after model calls; a result, not retried. */
    FAILED,
    /** The attempt may have sent requests but its outcome is not known (for example, it was interrupted). */
    UNKNOWN,
    /** The usage check failed (wrong served model, missing usage); halts the run. */
    STOPPED;

    public boolean retryable() {
        return this == SETUP_FAILED || this == RATE_LIMITED;
    }

    public boolean halting() {
        return this == STOPPED || this == PROVIDER_ERROR;
    }

    /** Whether spend for an attempt with this outcome may be higher than its usage log shows. */
    public boolean costUnclear() {
        return this == UNKNOWN || this == PROVIDER_ERROR || this == RATE_LIMITED || this == FAILED;
    }

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    @JsonCreator
    public static Outcome fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
