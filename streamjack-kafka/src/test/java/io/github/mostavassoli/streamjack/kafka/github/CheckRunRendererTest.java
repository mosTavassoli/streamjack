package io.github.mostavassoli.streamjack.kafka.github;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mostavassoli.streamjack.core.check.Category;
import io.github.mostavassoli.streamjack.core.check.Finding;
import io.github.mostavassoli.streamjack.core.evaluation.ScenarioResult;
import io.github.mostavassoli.streamjack.core.evaluation.SecurityVerdict;
import io.github.mostavassoli.streamjack.core.evaluation.UtilityVerdict;
import io.github.mostavassoli.streamjack.core.report.RunReport;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheckRunRendererTest {

    private final CheckRunRenderer renderer = new CheckRunRenderer();

    @Test
    void passingRunShowsASeparateSecurityAndUtilityVerdict() {
        CheckRunOutput output = render(result(SecurityVerdict.SECURE, UtilityVerdict.MET));

        assertThat(output.conclusion()).isEqualTo("success");
        assertThat(output.title()).isEqualTo("Security and utility passed");
        assertThat(output.summary()).contains("| SJ-001 | COMPLETED | SECURE | MET |");
        assertThat(output.text()).contains("No failed findings");
    }

    @Test
    void showsOnlySafeFindingNotesWhenSecurityIsCompromised() {
        String untrusted = "synthetic-canary @reviewer\n<script>untrusted</script>";
        CheckRunOutput output = renderer.render(RunReport.of(untrusted, List.of(new ScenarioResult(
                "SJ-001", untrusted, SecurityVerdict.COMPROMISED, UtilityVerdict.MET, List.of(
                        Finding.fail("canary-leak", Category.SECURITY, untrusted),
                        Finding.fail(untrusted, Category.SECURITY, untrusted),
                        Finding.pass("utility", Category.UTILITY, untrusted))))));

        assertThat(output.conclusion()).isEqualTo("failure");
        assertThat(output.summary()).contains("| SJ-001 | COMPLETED | COMPROMISED | MET |");
        assertThat(output.text()).contains(
                "**Failure:** A synthetic canary appeared in the evidence.",
                "**Failure:** A custom check failed.");
        assertThat(output.summary() + output.text()).doesNotContain("synthetic-canary", "@reviewer", "<script>");
    }

    @Test
    void warnsOnUtilityMissesWithoutFailingTheSecurityGate() {
        CheckRunOutput output = render(result(SecurityVerdict.SECURE, UtilityVerdict.NOT_MET,
                Finding.fail("utility", Category.UTILITY, "missing triage")));

        assertThat(output.conclusion()).isEqualTo("success");
        assertThat(output.title()).isEqualTo("Security gate passed with utility warnings");
        assertThat(output.summary()).contains("| SJ-001 | COMPLETED | SECURE | NOT_MET |");
        assertThat(output.text()).contains("**Warning:** An expected output was missing or did not match.");
    }

    @Test
    void failsTheGateOnInconclusiveEvidence() {
        CheckRunOutput output = render(ScenarioResult.inconclusive("SJ-002", "Two", "timed out"));

        assertThat(output.conclusion()).isEqualTo("failure");
        assertThat(output.summary()).contains("| SJ-002 | INCONCLUSIVE | INCONCLUSIVE | INCONCLUSIVE |");
        assertThat(output.text()).contains("**Failure:** Agent execution did not produce conclusive evidence.");
    }

    @Test
    void boundsLargeOutputsWithoutHidingTotalCounts() {
        ScenarioResult result = result(SecurityVerdict.COMPROMISED, UtilityVerdict.MET,
                Finding.fail("forbidden-tool", Category.SECURITY, "details"));
        CheckRunOutput output = renderer.render(RunReport.of("test", Collections.nCopies(300, result)));

        assertThat(output.summary()).contains("300 compromised", "50 more scenarios");
        assertThat(output.text()).contains("50 more findings");
        assertThat(output.summary().length()).isLessThan(65_535);
        assertThat(output.text().length()).isLessThan(65_535);
    }

    private CheckRunOutput render(ScenarioResult result) {
        return renderer.render(RunReport.of("sample-agent", List.of(result)));
    }

    private static ScenarioResult result(SecurityVerdict security, UtilityVerdict utility, Finding... findings) {
        return new ScenarioResult("SJ-001", "One", security, utility, List.of(findings));
    }
}
