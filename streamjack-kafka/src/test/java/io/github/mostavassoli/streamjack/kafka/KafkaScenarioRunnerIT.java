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
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers(disabledWithoutDocker = true)
class KafkaScenarioRunnerIT {

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

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

    @ParameterizedTest
    // A name starting with "__" is an ordinary topic unless the broker marks it internal.
    @ValueSource(strings = {"streamjack.it.unlisted-", "__streamjack.it.unlisted-"})
    void writeToATopicCreatedDuringTheRunIsObserved(String prefix) {
        Scenario scenario = loader.load(Path.of("..", "scenarios", "sj-001-payload-instruction-override.yaml"));
        // The topic does not exist when the run starts; the agent's write auto-creates it, as a write to a
        // disallowed topic such as support.published does on a fresh broker.
        String unlisted = prefix + UUID.randomUUID();
        RunnerConfiguration configuration = new RunnerConfiguration(
                KAFKA.getBootstrapServers(), Duration.ofSeconds(15), Duration.ofMillis(300), 100, true, Map.of());

        try (FakeTicketAgent agent = new FakeTicketAgent(KAFKA.getBootstrapServers(), CompletionMode.PROCESSED, unlisted)) {
            assertThat(agent.isRunning()).isTrue();
            ScenarioExecution execution = new KafkaScenarioRunner(configuration).run(scenario);

            assertThat(execution.evidence().outputRecords())
                    .as("records on the topic created during the run")
                    .anySatisfy(record -> assertThat(record.topic()).isEqualTo(unlisted));
            assertThat(execution.result().execution()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(execution.result().security()).isEqualTo(SecurityVerdict.COMPROMISED);
            assertThat(execution.result().findings())
                    .anySatisfy(finding -> assertThat(finding.message()).contains("non-allowed topic " + unlisted));
        }
    }

    @Test
    void writeBehindAnOpenTransactionIsInconclusiveRatherThanSecure() {
        Scenario scenario = loader.load(Path.of("..", "scenarios", "sj-001-payload-instruction-override.yaml"));
        // The agent leaves a transaction open on a new topic, then writes a normal record after it. The observer
        // reads committed data only, so that record stays hidden until the transaction ends (after this run).
        String hidden = "streamjack.it.hidden-" + UUID.randomUUID();
        RunnerConfiguration configuration = new RunnerConfiguration(
                KAFKA.getBootstrapServers(), Duration.ofSeconds(10), Duration.ofMillis(300), 100, true, Map.of());

        try (FakeTicketAgent agent = new FakeTicketAgent(
                KAFKA.getBootstrapServers(), CompletionMode.PROCESSED, hidden, true)) {
            assertThat(agent.isRunning()).isTrue();
            ScenarioExecution execution = new KafkaScenarioRunner(configuration).run(scenario);

            assertThat(execution.result().execution()).isEqualTo(ExecutionStatus.INCONCLUSIVE);
            assertThat(execution.result().security()).isEqualTo(SecurityVerdict.INCONCLUSIVE);
            assertThat(execution.result().findings()).singleElement()
                    .satisfies(finding -> assertThat(finding.message()).contains("quiet period"));
        }
    }

    @Test
    void deletingAnObservedTopicDuringTheRunIsInconclusive() throws Exception {
        Scenario scenario = loader.load(Path.of("..", "scenarios", "sj-001-payload-instruction-override.yaml"));
        // Records on a deleted topic can no longer be checked, so the run must not be reported as secure.
        String doomed = "streamjack.it.deleted-" + UUID.randomUUID();
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(java.util.List.of(new NewTopic(doomed, 1, (short) 1))).all().get();
        }
        RunnerConfiguration configuration = new RunnerConfiguration(
                KAFKA.getBootstrapServers(), Duration.ofSeconds(15), Duration.ofMillis(300), 100, true, Map.of());

        try (FakeTicketAgent agent = new FakeTicketAgent(KAFKA.getBootstrapServers(), CompletionMode.PROCESSED)) {
            agent.deleteOnFirstTicket(doomed);
            assertThat(agent.isRunning()).isTrue();
            ScenarioExecution execution = new KafkaScenarioRunner(configuration).run(scenario);

            assertThat(execution.result().execution()).isEqualTo(ExecutionStatus.INCONCLUSIVE);
            assertThat(execution.result().findings()).singleElement()
                    .satisfies(finding -> assertThat(finding.message()).contains(doomed, "deleted during the run"));
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
    void completionWithoutAFullQuietPeriodIsInconclusive() {
        Scenario scenario = loader.load(Path.of("..", "scenarios", "sj-001-payload-instruction-override.yaml"));
        Duration timeout = Duration.ofSeconds(8);
        // Leave no practical time to observe the completions and then fit the quiet period.
        RunnerConfiguration configuration = new RunnerConfiguration(
                KAFKA.getBootstrapServers(), timeout, timeout.minusNanos(1), 100, true, Map.of());

        try (FakeTicketAgent agent = new FakeTicketAgent(KAFKA.getBootstrapServers())) {
            assertThat(agent.isRunning()).isTrue();
            ScenarioExecution execution = new KafkaScenarioRunner(configuration).run(scenario);

            assertThat(execution.evidence().outputRecords()).hasSize(2);
            assertThat(execution.result().execution()).isEqualTo(ExecutionStatus.INCONCLUSIVE);
            assertThat(execution.result().security()).isEqualTo(SecurityVerdict.INCONCLUSIVE);
            assertThat(execution.result().utility()).isEqualTo(UtilityVerdict.INCONCLUSIVE);
            assertThat(execution.result().findings()).singleElement()
                    .satisfies(finding -> assertThat(finding.message()).contains("Timed out", "quiet period"));
        }
    }

    @Test
    void failedCompletionIsInconclusiveRatherThanSecure() {
        Scenario scenario = loader.load(Path.of("..", "scenarios", "sj-001-payload-instruction-override.yaml"));
        RunnerConfiguration configuration = new RunnerConfiguration(
                KAFKA.getBootstrapServers(), Duration.ofSeconds(10), Duration.ofMillis(100), 100, true, Map.of());

        try (FakeTicketAgent agent = new FakeTicketAgent(KAFKA.getBootstrapServers(), CompletionMode.FAILED)) {
            assertThat(agent.isRunning()).isTrue();
            ScenarioExecution execution = new KafkaScenarioRunner(configuration).run(scenario);

            assertThat(execution.result().execution()).isEqualTo(ExecutionStatus.INCONCLUSIVE);
            assertThat(execution.result().security()).isEqualTo(SecurityVerdict.INCONCLUSIVE);
            assertThat(execution.result().findings()).singleElement()
                    .satisfies(finding -> assertThat(finding.message())
                            .contains("Agent failed event", "TEST_FAILURE"));
        }
    }

    @ParameterizedTest
    @CsvSource({
        "DUPLICATE, duplicate event-completed record for event e1",
        "TRAILING_JSON, audit record is not valid JSON",
        "MISMATCHED_HEADER, event-completed sourceEventId does not match its Kafka header"
    })
    void invalidCompletionIsInconclusiveRatherThanSecure(CompletionMode mode, String diagnostic) {
        Scenario scenario = loader.load(Path.of("..", "scenarios", "sj-001-payload-instruction-override.yaml"));
        RunnerConfiguration configuration = new RunnerConfiguration(
                KAFKA.getBootstrapServers(), Duration.ofSeconds(8), Duration.ofMillis(300), 100, true, Map.of());

        try (FakeTicketAgent agent = new FakeTicketAgent(KAFKA.getBootstrapServers(), mode)) {
            assertThat(agent.isRunning()).isTrue();
            ScenarioExecution execution = new KafkaScenarioRunner(configuration).run(scenario);

            assertThat(execution.evidence().outputRecords()).hasSize(2);
            assertThat(execution.result().execution()).isEqualTo(ExecutionStatus.INCONCLUSIVE);
            assertThat(execution.result().security()).isEqualTo(SecurityVerdict.INCONCLUSIVE);
            assertThat(execution.result().utility()).isEqualTo(UtilityVerdict.INCONCLUSIVE);
            assertThat(execution.result().findings()).singleElement()
                    .satisfies(finding -> assertThat(finding.message()).contains(diagnostic));
        }
    }

    private enum CompletionMode {
        PROCESSED,
        FAILED,
        DUPLICATE,
        TRAILING_JSON,
        MISMATCHED_HEADER
    }

    private static final class FakeTicketAgent implements AutoCloseable {

        private final AtomicBoolean running = new AtomicBoolean(true);
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<?> task;
        private final CompletionMode completionMode;
        private final String extraTopic;
        private final boolean behindOpenTransaction;
        // Tickets retained from earlier tests are replayed on start; only tickets sent after this are the test's.
        private final long startedAtMillis = System.currentTimeMillis();

        private FakeTicketAgent(String bootstrapServers) {
            this(bootstrapServers, CompletionMode.PROCESSED);
        }

        private FakeTicketAgent(String bootstrapServers, CompletionMode completionMode) {
            this(bootstrapServers, completionMode, null);
        }

        private FakeTicketAgent(String bootstrapServers, CompletionMode completionMode, String extraTopic) {
            this(bootstrapServers, completionMode, extraTopic, false);
        }

        /**
         * @param extraTopic if not null, the agent also writes each new ticket to this topic.
         * @param behindOpenTransaction if true, that write follows a transaction the agent leaves open.
         */
        private FakeTicketAgent(String bootstrapServers, CompletionMode completionMode, String extraTopic,
                boolean behindOpenTransaction) {
            this.completionMode = completionMode;
            this.extraTopic = extraTopic;
            this.behindOpenTransaction = behindOpenTransaction;
            task = executor.submit(() -> run(bootstrapServers));
        }

        private volatile String topicToDelete;

        private boolean isRunning() {
            return running.get() && !task.isDone();
        }

        /** Makes the agent delete {@code topic} when it handles its first new ticket. */
        private void deleteOnFirstTicket(String topic) {
            topicToDelete = topic;
        }

        private void run(String bootstrapServers) {
            KafkaProducer<String, String> transactional = null;
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties(bootstrapServers));
                    KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties(bootstrapServers))) {
                consumer.subscribe(java.util.List.of("support.tickets"));
                while (running.get()) {
                    for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                        String eventId = header(record, AgentProtocol.EVENT_ID_HEADER);
                        String runId = header(record, AgentProtocol.RUN_ID_HEADER);
                        String category = "T-1000".equals(record.key()) ? "billing" : "other";
                        RecordHeaders headers = correlatedHeaders(runId, eventId);
                        String deletion = topicToDelete;
                        if (deletion != null && record.timestamp() >= startedAtMillis) {
                            topicToDelete = null;
                            try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrapServers))) {
                                admin.deleteTopics(java.util.List.of(deletion)).all().get();
                            }
                        }
                        if (extraTopic != null && record.timestamp() >= startedAtMillis) {
                            if (behindOpenTransaction && transactional == null) {
                                transactional = new KafkaProducer<>(transactionalProperties(bootstrapServers));
                                transactional.initTransactions();
                                transactional.beginTransaction();
                                transactional.send(new ProducerRecord<>(extraTopic, 0, record.key(), "{}",
                                        correlatedHeaders(runId, eventId))).get();
                                transactional.flush();
                            }
                            producer.send(new ProducerRecord<>(
                                            extraTopic, 0, record.key(), "{}", correlatedHeaders(runId, eventId)))
                                    .get();
                        }
                        producer.send(new ProducerRecord<>(
                                        "support.triage",
                                        null,
                                        record.key(),
                                        "{\"ticketId\":\"" + record.key() + "\",\"category\":\"" + category + "\"}",
                                        headers))
                                .get();
                        String completion = completionMode == CompletionMode.FAILED
                                ? "{\"kind\":\"event-completed\",\"sourceEventId\":\"" + eventId
                                        + "\",\"status\":\"failed\",\"errorCode\":\"TEST_FAILURE\"}"
                                : "{\"kind\":\"event-completed\",\"sourceEventId\":\"" + eventId
                                        + "\",\"status\":\"processed\"}";
                        if ("e1".equals(eventId)) {
                            switch (completionMode) {
                                case DUPLICATE -> sendCompletion(producer, record.key(), runId, eventId, completion);
                                case TRAILING_JSON -> completion += " {}";
                                case MISMATCHED_HEADER -> sendCompletion(producer, record.key(), runId, "e2", completion);
                                default -> { }
                            }
                        }
                        sendCompletion(producer, record.key(), runId, eventId, completion);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                if (transactional != null) {
                    // End the transaction so the shared broker holds no open transaction after this test.
                    transactional.abortTransaction();
                    transactional.close(Duration.ofSeconds(5));
                }
            }
        }

        private static Properties transactionalProperties(String bootstrapServers) {
            Properties properties = producerProperties(bootstrapServers);
            properties.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "fake-agent-" + UUID.randomUUID());
            return properties;
        }

        private static void sendCompletion(
                KafkaProducer<String, String> producer, String key, String runId, String eventId, String completion)
                throws java.util.concurrent.ExecutionException, InterruptedException {
            producer.send(new ProducerRecord<>(
                            AgentProtocol.AUDIT_TOPIC, null, key, completion, correlatedHeaders(runId, eventId)))
                    .get();
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
