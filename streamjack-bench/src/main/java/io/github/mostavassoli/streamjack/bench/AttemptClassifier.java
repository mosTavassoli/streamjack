package io.github.mostavassoli.streamjack.bench;

import io.github.mostavassoli.streamjack.core.check.Finding;
import io.github.mostavassoli.streamjack.core.evaluation.ExecutionStatus;
import io.github.mostavassoli.streamjack.core.evaluation.ScenarioResult;
import java.util.List;

/**
 * Decides how an attempt ended once its scenario has been started. From that point a request may have been sent, so
 * only an explicit rate limit with no paid response is retryable; everything else is a final result.
 */
final class AttemptClassifier {

    private AttemptClassifier() {
    }

    /**
     * @param result the runner's result, or null if it produced none
     * @param evidenceWritten whether the evidence file exists
     */
    static AttemptStatus classify(Trial trial, ScenarioResult result, boolean evidenceWritten, List<Usage.Entry> usage) {
        List<Usage.Entry> errors = usage.stream().filter(entry -> "error".equals(entry.outcome())).toList();
        long responses = usage.stream().filter(Usage.Entry::response).count();
        if (result == null) {
            return AttemptStatus.of(Outcome.UNKNOWN, "the runner produced no result", trial.events());
        }
        if (result.execution() == ExecutionStatus.COMPLETED && evidenceWritten && errors.isEmpty()) {
            return AttemptStatus.of(Outcome.COMPLETED, "", trial.events());
        }
        if (allCallsRateLimited(trial, usage)) {
            return AttemptStatus.of(Outcome.RATE_LIMITED, "provider rate limit", trial.events());
        }
        if (!errors.isEmpty()) {
            // Authentication, model access, an unsupported request, or a server error: stop and inspect.
            String types = String.join(" ", errors.stream().map(Usage.Entry::error)
                    .filter(java.util.Objects::nonNull).distinct().sorted().toList());
            return AttemptStatus.of(Outcome.PROVIDER_ERROR, types, trial.events());
        }
        // Only the runner's fixed text and the agent's validated error code are read, never free agent text.
        boolean invalidOutput = result.findings().stream().map(Finding::message)
                .anyMatch(message -> message.contains("with MODEL_OUTPUT_INVALID"));
        if (invalidOutput) {
            return AttemptStatus.of(Outcome.MODEL_OUTPUT_INVALID, "the agent rejected the model output", trial.events());
        }
        if (usage.isEmpty()) {
            return AttemptStatus.of(Outcome.UNKNOWN, "execution " + result.execution() + " and no model call was logged",
                    trial.events());
        }
        return AttemptStatus.of(Outcome.FAILED, "execution " + result.execution(), trial.events());
    }

    /**
     * Whether every expected model call ended in a rate limit and nothing else was logged. Otherwise another request
     * may have been processed, so the attempt is recorded as unknown rather than as a rate limit.
     */
    static boolean allCallsRateLimited(Trial trial, List<Usage.Entry> usage) {
        return usage.size() == trial.events() && !usage.isEmpty() && usage.stream().allMatch(AttemptClassifier::rateLimit);
    }

    /**
     * Checks a status, fresh or recovered from an earlier invocation, against the usage log, the single source for
     * what was sent: a logged provider error always halts the run, a setup failure must have sent nothing, a rate limit
     * must cover every expected call, and anything else stays as recorded.
     */
    static AttemptStatus reconcile(Trial trial, AttemptStatus status, List<Usage.Entry> usage) {
        List<String> providerErrors = usage.stream()
                .filter(entry -> "error".equals(entry.outcome()) && !rateLimit(entry))
                .map(entry -> String.valueOf(entry.error())).distinct().sorted().toList();
        if (!providerErrors.isEmpty() && status.outcome() != Outcome.PROVIDER_ERROR) {
            return withOutcome(status, Outcome.PROVIDER_ERROR, String.join(" ", providerErrors));
        }
        if (status.outcome() == Outcome.SETUP_FAILED && !usage.isEmpty()) {
            return withOutcome(status, Outcome.UNKNOWN, "setup reported a failure after model calls were logged");
        }
        if (status.outcome() == Outcome.RATE_LIMITED && !allCallsRateLimited(trial, usage)) {
            return withOutcome(status, Outcome.UNKNOWN, "rate limit with calls that may have been processed");
        }
        return status;
    }

    private static boolean rateLimit(Usage.Entry entry) {
        return "error".equals(entry.outcome()) && entry.error() != null && entry.error().contains("RateLimit");
    }

    private static AttemptStatus withOutcome(AttemptStatus status, Outcome outcome, String reason) {
        return new AttemptStatus(outcome, reason, status.events(), status.attempt(), status.started(), status.finished());
    }
}
