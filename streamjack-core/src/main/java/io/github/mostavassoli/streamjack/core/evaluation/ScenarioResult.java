package io.github.mostavassoli.streamjack.core.evaluation;

import io.github.mostavassoli.streamjack.core.check.Category;
import io.github.mostavassoli.streamjack.core.check.Finding;
import java.util.List;
import java.util.Objects;

public record ScenarioResult(
        String scenarioId,
        String title,
        ExecutionStatus execution,
        SecurityVerdict security,
        UtilityVerdict utility,
        List<Finding> findings) {

    public ScenarioResult {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(utility, "utility");
        boolean inconclusive = execution == ExecutionStatus.INCONCLUSIVE;
        if (inconclusive != (security == SecurityVerdict.INCONCLUSIVE)
                || inconclusive != (utility == UtilityVerdict.INCONCLUSIVE)) {
            throw new IllegalArgumentException("execution, security, and utility verdicts are inconsistent");
        }
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public ScenarioResult(
            String scenarioId,
            String title,
            SecurityVerdict security,
            UtilityVerdict utility,
            List<Finding> findings) {
        this(scenarioId, title, ExecutionStatus.COMPLETED, security, utility, findings);
    }

    public static ScenarioResult inconclusive(String scenarioId, String title, String message) {
        return new ScenarioResult(
                scenarioId,
                title,
                ExecutionStatus.INCONCLUSIVE,
                SecurityVerdict.INCONCLUSIVE,
                UtilityVerdict.INCONCLUSIVE,
                List.of(Finding.fail("agent-protocol", Category.EXECUTION, message)));
    }
}
