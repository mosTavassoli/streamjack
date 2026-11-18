package io.github.mostavassoli.streamjack.core.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mostavassoli.streamjack.core.check.Category;
import io.github.mostavassoli.streamjack.core.check.Finding;
import io.github.mostavassoli.streamjack.core.evaluation.ScenarioResult;
import io.github.mostavassoli.streamjack.core.evaluation.SecurityVerdict;
import io.github.mostavassoli.streamjack.core.evaluation.UtilityVerdict;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportWriterTest {

    @Test
    void writesVersionedReportWithSummary(@TempDir Path dir) throws Exception {
        List<ScenarioResult> results = List.of(
                new ScenarioResult("SJ-001", "One", SecurityVerdict.SECURE, UtilityVerdict.MET,
                        List.of(Finding.pass("forbidden-tool", Category.SECURITY, "ok"))),
                new ScenarioResult("SJ-002", "Two", SecurityVerdict.COMPROMISED, UtilityVerdict.NOT_MET,
                        List.of(Finding.fail("canary-leak", Category.SECURITY, "leaked"))),
                ScenarioResult.inconclusive("SJ-003", "Three", "completion timed out"));
        Path file = dir.resolve("out").resolve("report.json");

        new ReportWriter().write(RunReport.of("sample-agent", results), file);

        JsonNode json = new ObjectMapper().readTree(Files.readString(file));
        assertThat(json.get("reportVersion").asText()).isEqualTo(RunReport.REPORT_VERSION);
        assertThat(json.get("agent").asText()).isEqualTo("sample-agent");
        assertThat(json.at("/summary/scenarios").asInt()).isEqualTo(3);
        assertThat(json.at("/summary/completed").asInt()).isEqualTo(2);
        assertThat(json.at("/summary/inconclusive").asInt()).isEqualTo(1);
        assertThat(json.at("/summary/secure").asInt()).isEqualTo(1);
        assertThat(json.at("/summary/compromised").asInt()).isEqualTo(1);
        assertThat(json.at("/summary/utilityMet").asInt()).isEqualTo(1);
        assertThat(json.at("/summary/utilityNotMet").asInt()).isEqualTo(1);
        assertThat(json.at("/results/1/security").asText()).isEqualTo("COMPROMISED");
        assertThat(json.at("/results/1/findings/0/check").asText()).isEqualTo("canary-leak");
        assertThat(json.at("/results/1/findings/0/passed").asBoolean()).isFalse();
        assertThat(json.at("/results/2/execution").asText()).isEqualTo("INCONCLUSIVE");
        assertThat(json.at("/results/2/security").asText()).isEqualTo("INCONCLUSIVE");
    }
}
