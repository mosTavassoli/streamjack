package io.github.mostavassoli.streamjack.core.report;

import io.github.mostavassoli.streamjack.core.evaluation.ExecutionStatus;
import io.github.mostavassoli.streamjack.core.evaluation.ScenarioResult;
import io.github.mostavassoli.streamjack.core.evaluation.SecurityVerdict;
import io.github.mostavassoli.streamjack.core.evaluation.UtilityVerdict;
import java.util.List;

public record RunReport(String reportVersion, String agent, Summary summary, List<ScenarioResult> results) {

    public static final String REPORT_VERSION = "streamjack.dev/report/v1alpha2";

    public record Summary(
            int scenarios,
            int completed,
            int inconclusive,
            int secure,
            int compromised,
            int utilityMet,
            int utilityNotMet) {
    }

    public static RunReport of(String agent, List<ScenarioResult> results) {
        if (agent == null || agent.isBlank()) {
            throw new IllegalArgumentException("agent is required");
        }
        if (results == null) {
            throw new IllegalArgumentException("results are required");
        }
        int completed = count(results, ExecutionStatus.COMPLETED);
        int secure = count(results, SecurityVerdict.SECURE);
        int compromised = count(results, SecurityVerdict.COMPROMISED);
        int met = (int) results.stream().filter(result -> result.utility() == UtilityVerdict.MET).count();
        int notMet = (int) results.stream().filter(result -> result.utility() == UtilityVerdict.NOT_MET).count();
        Summary summary =
                new Summary(results.size(), completed, results.size() - completed, secure, compromised, met, notMet);
        return new RunReport(REPORT_VERSION, agent, summary, List.copyOf(results));
    }

    private static int count(List<ScenarioResult> results, ExecutionStatus status) {
        return (int) results.stream().filter(result -> result.execution() == status).count();
    }

    private static int count(List<ScenarioResult> results, SecurityVerdict verdict) {
        return (int) results.stream().filter(result -> result.security() == verdict).count();
    }
}
