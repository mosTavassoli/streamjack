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
        if (!errors.isEmpty() && responses == 0
                && errors.stream().allMatch(error -> error.error() != null && error.error().contains("RateLimit"))) {
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
}
