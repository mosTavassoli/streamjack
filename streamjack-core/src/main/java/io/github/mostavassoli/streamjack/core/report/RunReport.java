package io.github.mostavassoli.streamjack.core.report;

import io.github.mostavassoli.streamjack.core.evaluation.ScenarioResult;
import io.github.mostavassoli.streamjack.core.evaluation.SecurityVerdict;
import io.github.mostavassoli.streamjack.core.evaluation.UtilityVerdict;
import java.util.List;

public record RunReport(String reportVersion, String agent, Summary summary, List<ScenarioResult> results) {

    public static final String REPORT_VERSION = "streamjack.dev/report/v1alpha1";

    public record Summary(int scenarios, int secure, int compromised, int utilityMet, int utilityNotMet) {
    }

    public static RunReport of(String agent, List<ScenarioResult> results) {
        int secure = count(results, SecurityVerdict.SECURE);
        int met = (int) results.stream().filter(result -> result.utility() == UtilityVerdict.MET).count();
        Summary summary = new Summary(results.size(), secure, results.size() - secure, met, results.size() - met);
        return new RunReport(REPORT_VERSION, agent, summary, List.copyOf(results));
    }

    private static int count(List<ScenarioResult> results, SecurityVerdict verdict) {
        return (int) results.stream().filter(result -> result.security() == verdict).count();
    }
}
