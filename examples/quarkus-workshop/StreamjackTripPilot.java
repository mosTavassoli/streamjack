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
        if (args.length != 1 || !List.of("pending", "denial").contains(args[0])) {
            throw new IllegalArgumentException("Expected pending or denial");
        }
        boolean deny = args[0].equals("denial");
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
            boolean decisionAccepted = false;
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
                        if (!requestId.equals(output.path("requestId").asText())) {
                            continue;
                        }
                        String type = header(record, "ce_type");
                        boolean sameInstance = instanceId.equals(output.path("instanceId").asText())
                                && instanceId.equals(header(record, "ce_flowinstanceid"));
                        boolean planned = sameInstance && "com.tripplanner.trip.approval.requested".equals(type)
                                && "awaiting_approval".equals(output.path("status").asText())
                                && output.path("plan").isObject();
                        boolean rejected = deny && decisionAccepted && sameInstance
                                && "com.tripplanner.trip.rejected".equals(type)
                                && "rejected".equals(output.path("status").asText())
                                && output.path("confirmation").isNull();
                        String topic = planned ? "pilot.trip.plan" : rejected ? "pilot.trip.denied" : "pilot.trip.unexpected";
                        relay(producer, input, topic, record.value());
                        System.out.println("Observed workshop event: " + type);
                        if (deny && planned && !decisionAccepted) {
                            var decision = JSON.createObjectNode().put("instanceId", instanceId)
                                    .put("status", "rejected").put("reason", "Sandbox denial");
                            var response = send(http, "PUT", "/trip/approve", decision.toString(), 10, 202);
                            if (!instanceId.equals(response.path("instanceId").asText())
                                    || !requestId.equals(response.path("requestId").asText())
                                    || !"decision_submitted".equals(response.path("status").asText())) {
                                throw new IllegalStateException("Denial not accepted for this trip");
                            }
                            decisionAccepted = true;
                        }
                        boolean completed = deny ? rejected : planned;
                        if (completed || "com.tripplanner.trip.failed".equals(type)) {
                            var completion = JSON.createObjectNode().put("kind", "event-completed")
                                    .put("sourceEventId", header(input, EVENT))
                                    .put("status", completed ? "processed" : "failed");
                            if (!completed) {
                                completion.put("errorCode", "WORKSHOP_FLOW_FAILED");
                            }
                            relay(producer, input, "streamjack.audit", completion.toString());
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
