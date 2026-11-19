package io.github.mostavassoli.streamjack.core.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mostavassoli.streamjack.core.check.Category;
import io.github.mostavassoli.streamjack.core.check.Finding;
import io.github.mostavassoli.streamjack.core.evaluation.ScenarioResult;
import io.github.mostavassoli.streamjack.core.evaluation.SecurityVerdict;
import io.github.mostavassoli.streamjack.core.evaluation.UtilityVerdict;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportReaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ReportReader reader = new ReportReader();

    @Test
    void readsAReportWrittenByTheRunner(@TempDir Path dir) {
        RunReport report = sampleReport();
        Path file = dir.resolve("report.json");
        new ReportWriter().write(report, file);

        assertThat(reader.read(file)).isEqualTo(report);
    }

    @Test
    void rejectsOtherReportVersions() {
        ObjectNode tree = tree(sampleReport());
        tree.put("reportVersion", "streamjack.dev/report/v1alpha1");

        assertThatThrownBy(() -> parse(tree))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("reportVersion must be " + RunReport.REPORT_VERSION);
    }

    @Test
    void rejectsUnknownFields() {
        ObjectNode tree = tree(sampleReport());
        ((ObjectNode) tree.at("/results/0/findings/0")).put("severity", "high");

        assertThatThrownBy(() -> parse(tree))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("results[0].findings[0]", "severity");
    }

    @Test
    void rejectsDuplicateFields() {
        String json = """
                {"reportVersion":"%s","reportVersion":"%s"}
                """.formatted(RunReport.REPORT_VERSION, RunReport.REPORT_VERSION);

        assertThatThrownBy(() -> reader.parse(json.getBytes(StandardCharsets.UTF_8), "test.json"))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("not valid JSON", "Duplicate field");
    }

    @Test
    void rejectsTrailingContent() {
        byte[] json = (new ReportWriter().toJson(sampleReport()) + " {}").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.parse(json, "test.json"))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsVerdictsThatContradictFindings() {
        ObjectNode tree = tree(sampleReport());
        ((ObjectNode) tree.at("/results/1")).put("security", "SECURE");

        assertThatThrownBy(() -> parse(tree))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("results[1].security contradicts its security findings");
    }

    @Test
    void rejectsSummariesThatDoNotMatchResults() {
        ObjectNode tree = tree(sampleReport());
        ((ObjectNode) tree.at("/summary")).put("compromised", 0);

        assertThatThrownBy(() -> parse(tree))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("summary does not match results");
    }

    @Test
    void rejectsInconclusiveResultsWithConclusiveVerdicts() {
        ObjectNode tree = tree(sampleReport());
        ((ObjectNode) tree.at("/results/2")).put("security", "SECURE");

        assertThatThrownBy(() -> parse(tree))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("results[2]", "inconsistent");
    }

    @Test
    void rejectsReportsWithoutResults() {
        ObjectNode tree = tree(RunReport.of("sample-agent", List.of()));

        assertThatThrownBy(() -> parse(tree))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("results must contain at least one scenario");
    }

    @Test
    void rejectsCoercedScalars() {
        ObjectNode booleanAsText = tree(sampleReport());
        ((ObjectNode) booleanAsText.at("/results/0/findings/0")).put("passed", "true");
        ObjectNode numberAsText = tree(sampleReport());
        ((ObjectNode) numberAsText.at("/summary")).put("scenarios", "3");

        assertThatThrownBy(() -> parse(booleanAsText))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("results[0].findings[0].passed");
        assertThatThrownBy(() -> parse(numberAsText))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("summary.scenarios");
    }

    @Test
    void rejectsMissingNullAndBlankFields() {
        ObjectNode missingTitle = tree(sampleReport());
        ((ObjectNode) missingTitle.at("/results/0")).remove("title");
        ObjectNode nullAgent = tree(sampleReport());
        nullAgent.putNull("agent");
        ObjectNode blankScenarioId = tree(sampleReport());
        ((ObjectNode) blankScenarioId.at("/results/0")).put("scenarioId", " ");

        assertThatThrownBy(() -> parse(missingTitle))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("title");
        assertThatThrownBy(() -> parse(nullAgent))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("agent");
        assertThatThrownBy(() -> parse(blankScenarioId))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("results[0].scenarioId must match SJ-NNN");
    }

    @Test
    void rejectsDuplicateScenarioIdsAndNullResults() {
        ObjectNode duplicated = tree(sampleReport());
        ((ObjectNode) duplicated.at("/results/1")).put("scenarioId", "SJ-001");
        ObjectNode nullResult = tree(sampleReport());
        ((ArrayNode) nullResult.get("results")).addNull();

        assertThatThrownBy(() -> parse(duplicated))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("results[1].scenarioId is duplicated");
        assertThatThrownBy(() -> parse(nullResult))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("results[3] must not be null");
    }

    @Test
    void rejectsOversizedFiles(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("report.json");
        Files.writeString(file, new ReportWriter().toJson(sampleReport()));

        assertThatThrownBy(() -> new ReportReader(64).read(file))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("exceeds 64 bytes");
    }

    private RunReport parse(JsonNode tree) throws Exception {
        return reader.parse(JSON.writeValueAsBytes(tree), "test.json");
    }

    private static ObjectNode tree(RunReport report) {
        return JSON.valueToTree(report);
    }

    private static RunReport sampleReport() {
        return RunReport.of("sample-agent", List.of(
                new ScenarioResult("SJ-001", "One", SecurityVerdict.SECURE, UtilityVerdict.MET, List.of(
                        Finding.pass("forbidden-tool", Category.SECURITY, "ok"),
                        Finding.pass("utility", Category.UTILITY, "met"))),
                new ScenarioResult("SJ-002", "Two", SecurityVerdict.COMPROMISED, UtilityVerdict.NOT_MET, List.of(
                        Finding.fail("canary-leak", Category.SECURITY, "leaked"),
                        Finding.fail("utility", Category.UTILITY, "missing"))),
                ScenarioResult.inconclusive("SJ-003", "Three", "completion timed out")));
    }
}
