package io.github.mostavassoli.streamjack.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mostavassoli.streamjack.core.evaluation.ExecutionStatus;
import io.github.mostavassoli.streamjack.core.evaluation.SecurityVerdict;
import io.github.mostavassoli.streamjack.core.evaluation.UtilityVerdict;
import io.github.mostavassoli.streamjack.core.protocol.AgentProtocol;
import io.github.mostavassoli.streamjack.core.scenario.Scenario;
import io.github.mostavassoli.streamjack.core.scenario.ScenarioLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers(disabledWithoutDocker = true)
class KafkaScenarioRunnerIT {

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:4.1.1");

    private final ScenarioLoader loader = new ScenarioLoader();

    @Test
    void runsScenarioThroughARealKafkaBroker() {
        Scenario scenario = loader.load(Path.of("..", "scenarios", "sj-001-payload-instruction-override.yaml"));
        RunnerConfiguration configuration = new RunnerConfiguration(
                KAFKA.getBootstrapServers(), Duration.ofSeconds(15), Duration.ofMillis(300), 100, true, Map.of());

        try (FakeTicketAgent agent = new FakeTicketAgent(KAFKA.getBootstrapServers())) {
            assertThat(agent.isRunning()).isTrue();
            ScenarioExecution execution = new KafkaScenarioRunner(configuration).run(scenario);

            assertThat(execution.result().execution()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(execution.result().security()).isEqualTo(SecurityVerdict.SECURE);
            assertThat(execution.result().utility()).isEqualTo(UtilityVerdict.MET);
            assertThat(execution.evidence().outputRecords()).hasSize(2);
        }
    }

    @Test
    void missingCompletionIsInconclusiveRatherThanSecure() {
        Scenario scenario = loader.load(Path.of("..", "scenarios", "sj-001-payload-instruction-override.yaml"));
        RunnerConfiguration configuration = new RunnerConfiguration(
                KAFKA.getBootstrapServers(), Duration.ofSeconds(1), Duration.ofMillis(100), 100, true, Map.of());

        ScenarioExecution execution = new KafkaScenarioRunner(configuration).run(scenario);

        assertThat(execution.result().execution()).isEqualTo(ExecutionStatus.INCONCLUSIVE);
        assertThat(execution.result().security()).isEqualTo(SecurityVerdict.INCONCLUSIVE);
        assertThat(execution.result().utility()).isEqualTo(UtilityVerdict.INCONCLUSIVE);
        assertThat(execution.result().findings()).singleElement()
                .satisfies(finding -> assertThat(finding.message()).contains("Timed out", "e1", "e2"));
    }

    @Test
    void failedCompletionIsInconclusiveRatherThanSecure() {
        Scenario scenario = loader.load(Path.of("..", "scenarios", "sj-001-payload-instruction-override.yaml"));
        RunnerConfiguration configuration = new RunnerConfiguration(
                KAFKA.getBootstrapServers(), Duration.ofSeconds(10), Duration.ofMillis(100), 100, true, Map.of());

        try (FakeTicketAgent agent = new FakeTicketAgent(KAFKA.getBootstrapServers(), true)) {
            assertThat(agent.isRunning()).isTrue();
            ScenarioExecution execution = new KafkaScenarioRunner(configuration).run(scenario);

            assertThat(execution.result().execution()).isEqualTo(ExecutionStatus.INCONCLUSIVE);
            assertThat(execution.result().security()).isEqualTo(SecurityVerdict.INCONCLUSIVE);
            assertThat(execution.result().findings()).singleElement()
                    .satisfies(finding -> assertThat(finding.message())
                            .contains("Agent failed event", "TEST_FAILURE"));
        }
    }

    private static final class FakeTicketAgent implements AutoCloseable {

        private final AtomicBoolean running = new AtomicBoolean(true);
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<?> task;
        private final boolean fail;

        private FakeTicketAgent(String bootstrapServers) {
            this(bootstrapServers, false);
        }

        private FakeTicketAgent(String bootstrapServers, boolean fail) {
            this.fail = fail;
            task = executor.submit(() -> run(bootstrapServers));
        }

        private boolean isRunning() {
            return running.get() && !task.isDone();
        }

        private void run(String bootstrapServers) {
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties(bootstrapServers));
                    KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties(bootstrapServers))) {
                consumer.subscribe(java.util.List.of("support.tickets"));
                while (running.get()) {
                    for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                        String eventId = header(record, AgentProtocol.EVENT_ID_HEADER);
                        String runId = header(record, AgentProtocol.RUN_ID_HEADER);
                        String category = "T-1000".equals(record.key()) ? "billing" : "other";
                        RecordHeaders headers = correlatedHeaders(runId, eventId);
                        producer.send(new ProducerRecord<>(
                                        "support.triage",
                                        null,
                                        record.key(),
                                        "{\"ticketId\":\"" + record.key() + "\",\"category\":\"" + category + "\"}",
                                        headers))
                                .get();
                        String completion = fail
                                ? "{\"kind\":\"event-completed\",\"sourceEventId\":\"" + eventId
                                        + "\",\"status\":\"failed\",\"errorCode\":\"TEST_FAILURE\"}"
                                : "{\"kind\":\"event-completed\",\"sourceEventId\":\"" + eventId
                                        + "\",\"status\":\"processed\"}";
                        producer.send(new ProducerRecord<>(
                                        AgentProtocol.AUDIT_TOPIC,
                                        null,
                                        record.key(),
                                        completion,
                                        correlatedHeaders(runId, eventId)))
                                .get();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() {
            running.set(false);
            try {
                task.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                task.cancel(true);
            } finally {
                executor.shutdownNow();
            }
        }

        private static Properties consumerProperties(String bootstrapServers) {
            Properties properties = new Properties();
            properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            properties.put(ConsumerConfig.GROUP_ID_CONFIG, "fake-agent-" + UUID.randomUUID());
            properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
            return properties;
        }

        private static Properties producerProperties(String bootstrapServers) {
            Properties properties = new Properties();
            properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            return properties;
        }

        private static RecordHeaders correlatedHeaders(String runId, String eventId) {
            RecordHeaders headers = new RecordHeaders();
            headers.add(AgentProtocol.RUN_ID_HEADER, runId.getBytes(StandardCharsets.UTF_8));
            headers.add(AgentProtocol.EVENT_ID_HEADER, eventId.getBytes(StandardCharsets.UTF_8));
            headers.add(AgentProtocol.ORIGIN_HEADER, "fake-agent".getBytes(StandardCharsets.UTF_8));
            return headers;
        }

        private static String header(ConsumerRecord<String, String> record, String name) {
            Header header = record.headers().lastHeader(name);
            return new String(header.value(), StandardCharsets.UTF_8);
        }
    }
}
