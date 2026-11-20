import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

class StreamjackTripPilot {
    private static final String RUN = "streamjack-run-id";
    private static final String EVENT = "streamjack-event-id";
    private static final String ORIGIN = "streamjack-origin";
    private static final String INPUT = "pilot.trip.request";
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected pending, denial, or approval");
        }
        String decisionStatus = switch (args[0]) {
            case "pending" -> null;
            case "denial" -> "rejected";
            case "approval" -> "approved";
            default -> throw new IllegalArgumentException("Expected pending, denial, or approval");
        };
        var producerSettings = new Properties();
        producerSettings.setProperty("bootstrap.servers", "127.0.0.1:9092");
        var consumerSettings = new Properties();
        consumerSettings.putAll(producerSettings);
        consumerSettings.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "workshop-pilot-adapter");
        consumerSettings.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerSettings.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        try (var consumer = new KafkaConsumer<>(consumerSettings, new StringDeserializer(), new StringDeserializer());
                var producer = new KafkaProducer<>(producerSettings, new StringSerializer(), new StringSerializer());
                var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            consumer.subscribe(List.of(INPUT, "flow-out"));
            ConsumerRecord<String, String> input = null;
            String requestId = null;
            String instanceId = null;
            boolean planObserved = false;
            boolean decisionAccepted = false;
            boolean terminalObserved = false;
            long decisionOffset = Long.MAX_VALUE;
            while (true) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (INPUT.equals(record.topic())) {
                        if (input != null || !"runner".equals(header(record, ORIGIN))) {
                            continue;
                        }
                        if (header(record, RUN).isBlank() || header(record, EVENT).isBlank()) {
                            throw new IllegalStateException("Missing runner correlation headers");
                        }
                        input = record;
                        var response = send(http, "POST", "/trip/plan", input.value(), 35, 200);
                        requestId = response.path("requestId").asText("");
                        instanceId = response.path("instanceId").asText("");
                        if (requestId.isBlank() || instanceId.isBlank()) {
                            throw new IllegalStateException("Missing workshop request or instance ID");
                        }
                    } else if (input != null) {
                        var output = JSON.readTree(record.value());
                        boolean sameRequest = requestId.equals(output.path("requestId").asText());
                        boolean sameFlow = instanceId.equals(header(record, "ce_flowinstanceid"));
                        if (!sameRequest && !sameFlow) {
                            continue;
                        }
                        String type = header(record, "ce_type");
                        boolean sameInstance = sameRequest && sameFlow
                                && instanceId.equals(output.path("instanceId").asText());
                        boolean planned = sameInstance && !planObserved && !terminalObserved
                                && "com.tripplanner.trip.approval.requested".equals(type)
                                && "awaiting_approval".equals(output.path("status").asText())
                                && output.path("plan").isObject();
                        boolean awaitingOutcome = sameInstance && decisionAccepted && !terminalObserved
                                && record.offset() >= decisionOffset;
                        boolean rejected = awaitingOutcome && "rejected".equals(decisionStatus)
                                && "com.tripplanner.trip.rejected".equals(type)
                                && "rejected".equals(output.path("status").asText())
                                && output.path("confirmation").isNull();
                        var bookingReference = output.path("confirmation").path("bookingReference");
                        boolean confirmed = awaitingOutcome && "approved".equals(decisionStatus)
                                && "com.tripplanner.booking.finalized".equals(type)
                                && "confirmed".equals(output.path("status").asText())
                                && bookingReference.isTextual() && !bookingReference.textValue().isBlank();
                        // Extra outcomes remain evidence, even after the sole completion marker.
                        String topic = planned ? "pilot.trip.plan"
                                : rejected ? "pilot.trip.denied"
                                : confirmed ? "pilot.trip.confirmed" : "pilot.trip.unexpected";
                        relay(producer, input, topic, record.value());
                        System.out.println("Observed workshop event: " + type);
                        if (planned) {
                            planObserved = true;
                        }
                        if (planned && decisionStatus != null) {
                            // flow-out has one partition. Records already on the broker cannot
                            // satisfy the decision case, even if this poll delivers them later.
                            var outputPartition = new TopicPartition(record.topic(), record.partition());
                            decisionOffset = consumer.endOffsets(List.of(outputPartition), Duration.ofSeconds(5))
                                    .get(outputPartition);
                            var decision = JSON.createObjectNode().put("instanceId", instanceId)
                                    .put("status", decisionStatus).put("reason", "Sandbox decision");
                            var response = send(http, "PUT", "/trip/approve", decision.toString(), 10, 202);
                            if (!instanceId.equals(response.path("instanceId").asText())
                                    || !requestId.equals(response.path("requestId").asText())
                                    || !"decision_submitted".equals(response.path("status").asText())) {
                                throw new IllegalStateException("Decision not accepted for this trip");
                            }
                            decisionAccepted = true;
                        }
                        boolean completed = decisionStatus == null ? planned : rejected || confirmed;
                        boolean failed = sameInstance && "com.tripplanner.trip.failed".equals(type);
                        if (!terminalObserved && (completed || failed)) {
                            var completion = JSON.createObjectNode().put("kind", "event-completed")
                                    .put("sourceEventId", header(input, EVENT))
                                    .put("status", completed ? "processed" : "failed");
                            if (!completed) {
                                completion.put("errorCode", "WORKSHOP_FLOW_FAILED");
                            }
                            relay(producer, input, "streamjack.audit", completion.toString());
                            terminalObserved = true;
                        }
                    }
                }
            }
        }
    }

    private static JsonNode send(HttpClient http, String method, String path, String body, long timeout, int status)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18093" + path))
                .timeout(Duration.ofSeconds(timeout)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println(method + " " + path + ": HTTP " + response.statusCode());
        if (response.statusCode() != status) {
            throw new IllegalStateException("Unexpected workshop HTTP status");
        }
        return JSON.readTree(response.body());
    }

    private static void relay(KafkaProducer<String, String> producer, ConsumerRecord<String, String> input,
            String topic, String value) throws Exception {
        var headers = new RecordHeaders();
        for (String name : List.of(RUN, EVENT)) {
            headers.add(name, input.headers().lastHeader(name).value());
        }
        headers.add(ORIGIN, "quarkus-workshop-pilot".getBytes(StandardCharsets.UTF_8));
        producer.send(new ProducerRecord<>(topic, null, input.key(), value, headers)).get(10, TimeUnit.SECONDS);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }
}
