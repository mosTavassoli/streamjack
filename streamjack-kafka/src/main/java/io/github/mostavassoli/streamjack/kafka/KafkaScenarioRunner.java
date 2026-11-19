package io.github.mostavassoli.streamjack.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mostavassoli.streamjack.core.evaluation.Evaluator;
import io.github.mostavassoli.streamjack.core.evaluation.ScenarioResult;
import io.github.mostavassoli.streamjack.core.evidence.Evidence;
import io.github.mostavassoli.streamjack.core.evidence.ObservedRecord;
import io.github.mostavassoli.streamjack.core.evidence.ToolCall;
import io.github.mostavassoli.streamjack.core.protocol.AgentProtocol;
import io.github.mostavassoli.streamjack.core.scenario.Scenario;
import io.github.mostavassoli.streamjack.core.scenario.ScenarioEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

public final class KafkaScenarioRunner {

    private static final Pattern ALL_NON_INTERNAL_TOPICS = Pattern.compile("^(?!__).+");
    private static final Duration ASSIGNMENT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private final RunnerConfiguration configuration;
    private final Evaluator evaluator;
    private final AuditEventParser auditParser;

    public KafkaScenarioRunner(RunnerConfiguration configuration) {
        this(configuration, Evaluator.withDefaultChecks(), new ObjectMapper());
    }

    KafkaScenarioRunner(RunnerConfiguration configuration, Evaluator evaluator, ObjectMapper json) {
        this.configuration = configuration;
        this.evaluator = evaluator;
        this.auditParser = new AuditEventParser(json);
    }

    public ScenarioExecution run(Scenario scenario) {
        Instant startedAt = Instant.now();
        String runId = UUID.randomUUID().toString();
        if (configuration.createTopics()) {
            createTopics(scenario);
        }

        List<ObservedRecord> outputRecords = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        List<String> protocolProblems = new ArrayList<>();
        Set<String> terminalEvents = new HashSet<>();
        Set<String> expectedEvents = scenario.spec().events().stream()
                .map(ScenarioEvent::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        boolean quietPeriodCompleted;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties());
                KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties())) {
            consumer.subscribe(ALL_NON_INTERNAL_TOPICS);
            awaitAssignmentAndSeekToEnd(consumer);
            produceScenario(producer, scenario, runId);
            quietPeriodCompleted = collectEvidence(
                    consumer,
                    runId,
                    expectedEvents,
                    terminalEvents,
                    outputRecords,
                    toolCalls,
                    protocolProblems);
        }

        Evidence evidence = new Evidence(outputRecords, toolCalls);
        ScenarioResult result;
        if (!protocolProblems.isEmpty()) {
            result = ScenarioResult.inconclusive(
                    scenario.id(), scenario.metadata().title(), String.join("; ", protocolProblems));
        } else if (!terminalEvents.containsAll(expectedEvents)) {
            Set<String> missing = new LinkedHashSet<>(expectedEvents);
            missing.removeAll(terminalEvents);
            result = ScenarioResult.inconclusive(
                    scenario.id(),
                    scenario.metadata().title(),
                    "Timed out waiting for completion of event(s) " + missing);
        } else if (!quietPeriodCompleted) {
            result = ScenarioResult.inconclusive(
                    scenario.id(), scenario.metadata().title(), "Timed out before the observation quiet period completed");
        } else {
            result = evaluator.evaluate(scenario, evidence);
        }
        return new ScenarioExecution(runId, Duration.between(startedAt, Instant.now()), evidence, result);
    }

    private void createTopics(Scenario scenario) {
        Set<String> topics = new HashSet<>();
        scenario.spec().events().forEach(event -> topics.add(event.topic()));
        topics.addAll(scenario.spec().policy().allowedOutputTopics());
        topics.add(AgentProtocol.AUDIT_TOPIC);

        Properties properties = baseProperties();
        properties.put(AdminClientConfig.CLIENT_ID_CONFIG, "streamjack-admin-" + scenario.id().toLowerCase());
        try (AdminClient admin = AdminClient.create(properties)) {
            Map<String, org.apache.kafka.common.KafkaFuture<Void>> creations = admin.createTopics(
                            topics.stream().map(topic -> new NewTopic(topic, 1, (short) 1)).toList())
                    .values();
            for (Map.Entry<String, org.apache.kafka.common.KafkaFuture<Void>> creation : creations.entrySet()) {
                try {
                    creation.getValue().get(configuration.observationTimeout().toMillis(), TimeUnit.MILLISECONDS);
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw new KafkaRunnerException("Cannot create topic " + creation.getKey(), e.getCause());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new KafkaRunnerException("Interrupted while creating topic " + creation.getKey(), e);
                } catch (TimeoutException e) {
                    throw new KafkaRunnerException("Timed out creating topic " + creation.getKey(), e);
                }
            }
        }
    }

    private void awaitAssignmentAndSeekToEnd(KafkaConsumer<String, String> consumer) {
        Instant deadline = Instant.now().plus(min(configuration.observationTimeout(), ASSIGNMENT_TIMEOUT));
        while (consumer.assignment().isEmpty() && Instant.now().isBefore(deadline)) {
            consumer.poll(POLL_INTERVAL);
        }
        if (consumer.assignment().isEmpty()) {
            throw new KafkaRunnerException("Kafka consumer received no topic assignment");
        }
        consumer.seekToEnd(consumer.assignment());
        consumer.assignment().forEach(consumer::position);
    }

    private void produceScenario(KafkaProducer<String, String> producer, Scenario scenario, String runId) {
        for (ScenarioEvent event : scenario.spec().events()) {
            RecordHeaders headers = new RecordHeaders();
            event.headers().forEach((key, value) -> headers.add(key, utf8(value)));
            headers.add(AgentProtocol.RUN_ID_HEADER, utf8(runId));
            headers.add(AgentProtocol.EVENT_ID_HEADER, utf8(event.id()));
            headers.add(AgentProtocol.ORIGIN_HEADER, utf8(AgentProtocol.RUNNER_ORIGIN));
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(event.topic(), 0, event.key(), event.value().toString(), headers);
            try {
                producer.send(record).get(configuration.observationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KafkaRunnerException("Interrupted while producing event " + event.id(), e);
            } catch (ExecutionException | TimeoutException e) {
                throw new KafkaRunnerException("Cannot produce event " + event.id(), e);
            }
        }
        producer.flush();
    }

    private boolean collectEvidence(
            KafkaConsumer<String, String> consumer,
            String runId,
            Set<String> expectedEvents,
            Set<String> terminalEvents,
            List<ObservedRecord> outputRecords,
            List<ToolCall> toolCalls,
            List<String> protocolProblems) {
        long started = System.nanoTime();
        Duration timeout = configuration.observationTimeout();
        Duration quietUntil = null;
        int evidenceRecords = 0;

        while (true) {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            if (quietUntil != null && quietUntil.compareTo(timeout) <= 0 && elapsed.compareTo(quietUntil) >= 0) {
                return true;
            }
            if (elapsed.compareTo(timeout) >= 0) {
                return false;
            }
            Duration nextBoundary = quietUntil == null ? timeout : min(quietUntil, timeout);
            ConsumerRecords<String, String> records = consumer.poll(min(POLL_INTERVAL, nextBoundary.minus(elapsed)));
            boolean matchingActivity = false;
            for (ConsumerRecord<String, String> record : records) {
                if (!hasHeaderValue(record.headers(), AgentProtocol.RUN_ID_HEADER, runId)) {
                    continue;
                }
                if (headerCount(record.headers(), AgentProtocol.ORIGIN_HEADER) == 1
                        && AgentProtocol.RUNNER_ORIGIN.equals(
                                lastHeader(record.headers(), AgentProtocol.ORIGIN_HEADER))) {
                    continue;
                }
                matchingActivity = true;
                evidenceRecords++;
                if (evidenceRecords > configuration.maxEvidenceRecords()) {
                    protocolProblems.add("Evidence limit of " + configuration.maxEvidenceRecords() + " records exceeded");
                    return false;
                }
                String correlationProblem = correlationProblem(record.headers(), runId, expectedEvents);
                if (correlationProblem != null) {
                    protocolProblems.add(correlationProblem + " on topic " + record.topic());
                    continue;
                }
                if (AgentProtocol.AUDIT_TOPIC.equals(record.topic())) {
                    collectAudit(record, expectedEvents, terminalEvents, toolCalls, protocolProblems);
                } else {
                    outputRecords.add(new ObservedRecord(
                            record.topic(), record.key(), headersToMap(record.headers()), record.value()));
                }
            }

            if (terminalEvents.containsAll(expectedEvents) && (matchingActivity || quietUntil == null)) {
                quietUntil = Duration.ofNanos(System.nanoTime() - started).plus(configuration.quietPeriod());
            }
        }
    }

    private void collectAudit(
            ConsumerRecord<String, String> record,
            Set<String> expectedEvents,
            Set<String> terminalEvents,
            List<ToolCall> toolCalls,
            List<String> protocolProblems) {
        AuditEventParser.AuditEvent event;
        try {
            event = auditParser.parse(record.value());
        } catch (AuditEventParser.ProtocolException e) {
            protocolProblems.add(e.getMessage());
            return;
        }
        String headerEventId = lastHeader(record.headers(), AgentProtocol.EVENT_ID_HEADER);
        if (event instanceof AuditEventParser.ToolCallEvent toolCallEvent) {
            ToolCall toolCall = toolCallEvent.toolCall();
            if (!expectedEvents.contains(toolCall.sourceEventId())) {
                protocolProblems.add("tool-call references unknown event " + toolCall.sourceEventId());
            } else if (!toolCall.sourceEventId().equals(headerEventId)) {
                protocolProblems.add("tool-call sourceEventId does not match its Kafka header");
            } else {
                toolCalls.add(toolCall);
            }
            return;
        }

        AuditEventParser.CompletionEvent completion = (AuditEventParser.CompletionEvent) event;
        if (!expectedEvents.contains(completion.sourceEventId())) {
            protocolProblems.add("event-completed references unknown event " + completion.sourceEventId());
        } else if (!completion.sourceEventId().equals(headerEventId)) {
            protocolProblems.add("event-completed sourceEventId does not match its Kafka header");
        } else if (AgentProtocol.FAILED_STATUS.equals(completion.status())) {
            if (!terminalEvents.add(completion.sourceEventId())) {
                protocolProblems.add("duplicate event-completed record for event " + completion.sourceEventId());
            } else {
                protocolProblems.add(
                        "Agent failed event " + completion.sourceEventId() + " with " + completion.errorCode());
            }
        } else if (!terminalEvents.add(completion.sourceEventId())) {
            protocolProblems.add("duplicate event-completed record for event " + completion.sourceEventId());
        } else {
            return;
        }
    }

    private Properties baseProperties() {
        Properties properties = new Properties();
        configuration.kafkaProperties().forEach(properties::put);
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, configuration.bootstrapServers());
        return properties;
    }

    private Properties producerProperties() {
        Properties properties = baseProperties();
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, "streamjack-producer");
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        return properties;
    }

    private Properties consumerProperties() {
        Properties properties = baseProperties();
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, "streamjack-observer");
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "streamjack-" + UUID.randomUUID());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        properties.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, 500);
        properties.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        return properties;
    }

    private static Map<String, String> headersToMap(Headers headers) {
        Map<String, String> flattened = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Header header : headers) {
            int occurrence = counts.merge(header.key(), 1, Integer::sum);
            String key = occurrence == 1 ? header.key() : header.key() + "#" + occurrence;
            flattened.put(
                    key, header.value() == null ? "<null>" : new String(header.value(), StandardCharsets.UTF_8));
        }
        return flattened;
    }

    private static String lastHeader(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        return header == null || header.value() == null
                ? null
                : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String correlationProblem(Headers headers, String runId, Set<String> expectedEvents) {
        if (headerCount(headers, AgentProtocol.RUN_ID_HEADER) != 1
                || !runId.equals(lastHeader(headers, AgentProtocol.RUN_ID_HEADER))) {
            return "record must contain exactly one matching " + AgentProtocol.RUN_ID_HEADER;
        }
        String eventId = lastHeader(headers, AgentProtocol.EVENT_ID_HEADER);
        if (headerCount(headers, AgentProtocol.EVENT_ID_HEADER) != 1
                || eventId == null
                || !expectedEvents.contains(eventId)) {
            return "record has a missing or unknown " + AgentProtocol.EVENT_ID_HEADER;
        }
        String origin = lastHeader(headers, AgentProtocol.ORIGIN_HEADER);
        if (headerCount(headers, AgentProtocol.ORIGIN_HEADER) != 1
                || origin == null
                || origin.isBlank()
                || AgentProtocol.RUNNER_ORIGIN.equals(origin)) {
            return "record has an invalid " + AgentProtocol.ORIGIN_HEADER;
        }
        return null;
    }

    private static int headerCount(Headers headers, String name) {
        int count = 0;
        for (Header ignored : headers.headers(name)) {
            count++;
        }
        return count;
    }

    private static boolean hasHeaderValue(Headers headers, String name, String expectedValue) {
        for (Header header : headers.headers(name)) {
            if (header.value() != null
                    && expectedValue.equals(new String(header.value(), StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    public static final class KafkaRunnerException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        KafkaRunnerException(String message) {
            super(message);
        }

        KafkaRunnerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
