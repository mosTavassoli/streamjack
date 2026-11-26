package io.github.mostavassoli.streamjack.core.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The defense-levels v2 benchmark compares each attack with a matched control. This test keeps them matched: an attack
 * may differ from its control only by text added to one event's body or by the value of an existing header. Every
 * other field, event, and the shared calibration ticket must be identical.
 */
class BenchmarkV2ScenarioPairsTest {

    private static final Map<String, Scenario> SCENARIOS = new ScenarioLoader()
            .loadDirectory(Path.of("..", "experiments", "defense-levels-v2", "scenarios")).stream()
            .collect(Collectors.toMap(Scenario::id, Function.identity()));

    @ParameterizedTest
    @CsvSource({
        "SJ-101, SJ-102", "SJ-101, SJ-103",
        "SJ-111, SJ-112", "SJ-111, SJ-113",
        "SJ-121, SJ-122", "SJ-121, SJ-123",
        "SJ-131, SJ-132", "SJ-131, SJ-133"
    })
    void attackDiffersFromItsControlOnlyInTheInjectedText(String controlId, String attackId) {
        Spec control = SCENARIOS.get(controlId).spec();
        Spec attack = SCENARIOS.get(attackId).spec();

        assertThat(attack.events()).hasSameSizeAs(control.events());
        assertThat(attack.policy()).isEqualTo(control.policy());
        assertThat(attack.utility()).isEqualTo(control.utility());
        assertThat(attack.canaries()).isEqualTo(control.canaries());
        int changed = 0;
        for (int i = 0; i < control.events().size(); i++) {
            ScenarioEvent before = control.events().get(i);
            ScenarioEvent after = attack.events().get(i);
            assertThat(after.id()).isEqualTo(before.id());
            assertThat(after.topic()).isEqualTo(before.topic());
            assertThat(after.key()).isEqualTo(before.key());
            assertThat(after.headers().keySet()).isEqualTo(before.headers().keySet());
            assertThat(after.value().path("ticketId")).isEqualTo(before.value().path("ticketId"));
            assertThat(after.value().path("customerId")).isEqualTo(before.value().path("customerId"));
            assertThat(after.value().path("subject")).isEqualTo(before.value().path("subject"));
            // Collect every text field of the event that differs: the body and each header value.
            java.util.Map<String, String[]> differences = new java.util.LinkedHashMap<>();
            String bodyBefore = before.value().path("body").asText();
            String bodyAfter = after.value().path("body").asText();
            if (!bodyAfter.equals(bodyBefore)) {
                differences.put("body", new String[] {bodyBefore, bodyAfter});
            }
            for (String header : before.headers().keySet()) {
                if (!after.headers().get(header).equals(before.headers().get(header))) {
                    differences.put("header " + header, new String[] {before.headers().get(header), after.headers().get(header)});
                }
            }
            if (differences.isEmpty()) {
                assertThat(after.role()).isEqualTo(before.role());
                continue;
            }
            changed++;
            assertThat(after.role()).isEqualTo(EventRole.ATTACK);
            assertThat(differences).as("exactly one field carries the injection").hasSize(1);
            String[] texts = differences.values().iterator().next();
            // The attack keeps the whole control text and only appends to it.
            assertThat(texts[1]).as("attack text keeps the control text").startsWith(texts[0] + " ");
        }
        assertThat(changed).as("exactly one event carries the injection").isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"SJ-101", "SJ-111", "SJ-121", "SJ-131", "SJ-141"})
    void everyScenarioStartsWithTheSameCalibrationTicket(String id) {
        List<ScenarioEvent> events = SCENARIOS.get(id).spec().events();
        ScenarioEvent first = events.get(0);
        assertThat(first.key()).isEqualTo("T-1900");
        assertThat(first.role()).isEqualTo(EventRole.BENIGN);
        assertThat(first.value().path("customerId").asText()).isEqualTo("C-1004");
        assertThat(events.subList(1, events.size()))
                .noneMatch(event -> event.value().path("customerId").asText().equals("C-1004")
                        && !id.equals("SJ-141"));
    }
}
