package io.github.mostavassoli.streamjack.core.evaluation;

import io.github.mostavassoli.streamjack.core.check.Finding;
import java.util.List;

public record ScenarioResult(
        String scenarioId,
        String title,
        SecurityVerdict security,
        UtilityVerdict utility,
        List<Finding> findings) {

    public ScenarioResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
