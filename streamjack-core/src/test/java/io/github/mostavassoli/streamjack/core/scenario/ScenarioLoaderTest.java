package io.github.mostavassoli.streamjack.core.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScenarioLoaderTest {

    private static final Path SCENARIOS = Path.of("..", "scenarios");

    private final ScenarioLoader loader = new ScenarioLoader();

    @Test
    void loadsEveryBundledScenario() {
        List<Scenario> scenarios = loader.loadDirectory(SCENARIOS);

        assertThat(scenarios).extracting(Scenario::id)
                .containsExactly("SJ-001", "SJ-002", "SJ-003", "SJ-004", "SJ-005");
        assertThat(scenarios).allSatisfy(scenario -> {
            assertThat(scenario.spec().events()).isNotEmpty();
            assertThat(scenario.spec().utility()).isNotEmpty();
        });
    }

    @Test
    void parsesHeadersRolesAndPolicy() {
        Scenario scenario = loader.load(SCENARIOS.resolve("sj-003-header-smuggling.yaml"));

        ScenarioEvent attack = scenario.spec().events().get(1);
        assertThat(attack.role()).isEqualTo(EventRole.ATTACK);
        assertThat(attack.headers()).containsKey("x-customer-note");
        assertThat(attack.value().get("ticketId").asText()).isEqualTo("T-3001");
        assertThat(scenario.spec().policy().allowedOutputTopics()).containsExactly("support.triage");
    }

    @Test
    void rejectsUnknownFields() {
        String yaml = valid().replace("  title: Example", "  title: Example\n  severity: high");

        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ScenarioException.class)
                .hasMessageContaining("severity");
    }

    @Test
    void rejectsDuplicateKeys() {
        String yaml = valid().replace("  title: Example", "  title: Example\n  title: Again");

        assertThatThrownBy(() -> load(yaml)).isInstanceOf(ScenarioException.class);
    }

    @Test
    void rejectsWrongApiVersion() {
        String yaml = valid().replace("streamjack.dev/v1alpha1", "streamjack.dev/v9");

        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ScenarioException.class)
                .hasMessageContaining("apiVersion");
    }

    @Test
    void rejectsCanaryThatAppearsInAnEvent() {
        String yaml = valid().replace("body: Hello", "body: Hello SJ-CANARY-TEST-0123456789");

        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ScenarioException.class)
                .hasMessageContaining("canaries must only be reachable through agent tools");
    }

    @Test
    void rejectsShortCanary() {
        String yaml = valid().replace("SJ-CANARY-TEST-0123456789", "short");

        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ScenarioException.class)
                .hasMessageContaining("at least 16 characters");
    }

    @Test
    void rejectsReservedRunnerHeaders() {
        String yaml = valid().replace(
                "      key: T-1\n      value:",
                "      key: T-1\n      headers:\n        streamjack-run-id: forged\n      value:");

        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ScenarioException.class)
                .hasMessageContaining("reserved control header streamjack-run-id");
    }

    @Test
    void rejectsInvalidKafkaTopicsAndToolNames() {
        String yaml = valid()
                .replace("topic: support.tickets", "topic: invalid topic")
                .replace("forbiddenTools: [publishReply]", "forbiddenTools: [_publish]");

        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ScenarioException.class)
                .hasMessageContaining("valid Kafka topic", "invalid tool name");
    }

    @Test
    void rejectsTheReservedAuditTopicAsInput() {
        String yaml = valid().replace("topic: support.tickets", "topic: streamjack.audit");

        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ScenarioException.class)
                .hasMessageContaining("reserved audit topic streamjack.audit");
    }

    @Test
    void requiresABenignEvent() {
        String yaml = valid().replace("role: benign", "role: attack");

        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ScenarioException.class)
                .hasMessageContaining("at least one benign event");
    }

    @Test
    void requiresUtilityTopicToBeAllowed() {
        String yaml = valid().replace("    - id: triaged\n      topic: support.triage",
                "    - id: triaged\n      topic: somewhere.else");

        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(ScenarioException.class)
                .hasMessageContaining("not an allowed output topic");
    }

    @Test
    void acceptsAToolCallUtilityExpectation() {
        Scenario scenario = load(valid() + """
                    - id: followed-up
                      tool: createFollowUp
                      arguments:
                        ticketId: T-1
                """);

        UtilityExpectation expectation = scenario.spec().utility().get(1);
        assertThat(expectation.tool()).isEqualTo("createFollowUp");
        assertThat(expectation.topic()).isNull();
        assertThat(expectation.arguments()).containsEntry("ticketId", "T-1");
    }

    @Test
    void rejectsUtilityExpectationWithBothOrNeitherTopicAndTool() {
        String both = valid().replace("      topic: support.triage\n      key: T-1",
                "      topic: support.triage\n      tool: createFollowUp\n      key: T-1");
        String neither = valid().replace("      topic: support.triage\n", "");

        assertThatThrownBy(() -> load(both)).hasMessageContaining("exactly one of topic or tool");
        assertThatThrownBy(() -> load(neither)).hasMessageContaining("exactly one of topic or tool");
    }

    @Test
    void rejectsToolExpectationThatIsForbiddenOrHasRecordFields() {
        String yaml = valid() + """
                    - id: published
                      tool: publishReply
                      key: T-1
                """;

        assertThatThrownBy(() -> load(yaml))
                .hasMessageContaining("is also a forbidden tool", "apply only to topic expectations");
    }

    @Test
    void reportsEveryProblemAtOnce() {
        String yaml = valid()
                .replace("id: SJ-999", "id: bad")
                .replace("role: benign", "role: attack");

        assertThatThrownBy(() -> load(yaml))
                .isInstanceOfSatisfying(ScenarioException.class, e -> assertThat(e.problems()).hasSize(2));
    }

    private Scenario load(String yaml) {
        return loader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.yaml");
    }

    static String valid() {
        return """
                apiVersion: streamjack.dev/v1alpha1
                kind: Scenario
                metadata:
                  id: SJ-999
                  title: Example
                spec:
                  canaries:
                    - id: key
                      value: SJ-CANARY-TEST-0123456789
                  events:
                    - id: e1
                      role: benign
                      topic: support.tickets
                      key: T-1
                      value:
                        ticketId: T-1
                        body: Hello
                  policy:
                    forbiddenTools: [publishReply]
                    allowedOutputTopics: [support.triage]
                  utility:
                    - id: triaged
                      topic: support.triage
                      key: T-1
                      fields:
                        category: billing
                """;
    }
}
