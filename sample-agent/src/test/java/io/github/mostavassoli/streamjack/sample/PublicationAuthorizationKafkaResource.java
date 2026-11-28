package io.github.mostavassoli.streamjack.sample;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import io.github.mostavassoli.streamjack.core.protocol.AgentProtocol;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

public final class PublicationAuthorizationKafkaResource implements QuarkusTestResourceLifecycleManager {

    static final String TICKETS = "support.tickets";
    static final String ORDERS = "support.orders";
    static final String TRIAGE = "support.triage";
    static final String PUBLISHED = "support.published";
    static final String AGENT = "triage-agent";
    static final String PUBLISHER = "fixture-publisher";
    /** Untrusted producers write only here; the gateway authenticates them and republishes to the ticket topic. */
    static final String INTAKE = "support.intake";
    static final String GATEWAY = "ticket-gateway";
    static final String CUSTOMER_APP = "customer-app";
    private static final String ADMIN = "fixture-admin";
    private static final String GROUP = "streamjack-sample-agent";
    private static final int CLIENT_PORT = 9092;

    private final String adminPassword = UUID.randomUUID().toString();
    private final String agentPassword = UUID.randomUUID().toString();
    private final String publisherPassword = UUID.randomUUID().toString();
    private final String gatewayPassword = UUID.randomUUID().toString();
    private final String customerAppPassword = UUID.randomUUID().toString();
    private AuthorizationBroker kafka;

    @Override
    public Map<String, String> start() {
        kafka = new AuthorizationBroker();
        try {
            kafka.start();
            provision();
            return agentConfig();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            throw new IllegalStateException("Interrupted while setting up the local ACL test", e);
        } catch (Exception e) {
            stop();
            throw new IllegalStateException("Could not set up the local Kafka ACL test", e);
        }
    }

    @Override
    public void inject(TestInjector injector) {
        injector.injectIntoFields(this, field -> field.getType().equals(PublicationAuthorizationKafkaResource.class));
    }

    @Override
    public void stop() {
        if (kafka != null) {
            kafka.stop();
            kafka = null;
        }
    }

    KafkaProducer<String, String> ticketProducer() {
        return producer(ADMIN, adminPassword);
    }

    KafkaProducer<String, String> authorizedPublisher() {
        return producer(PUBLISHER, publisherPassword);
    }

    KafkaProducer<String, String> gatewayProducer() {
        return producer(GATEWAY, gatewayPassword);
    }

    KafkaProducer<String, String> customerAppProducer() {
        return producer(CUSTOMER_APP, customerAppPassword);
    }

    KafkaConsumer<String, String> observer() {
        Map<String, Object> settings = clientSettings(ADMIN, adminPassword);
        settings.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        settings.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(settings);
    }

    private KafkaProducer<String, String> producer(String user, String password) {
        Map<String, Object> settings = clientSettings(user, password);
        settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaProducer<>(settings);
    }

    private Map<String, Object> clientSettings(String user, String password) {
        return new HashMap<>(Map.of(
                CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers(),
                CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT",
                SaslConfigs.SASL_MECHANISM, "PLAIN",
                SaslConfigs.SASL_JAAS_CONFIG, jaas(user, password)));
    }

    private Map<String, String> agentConfig() {
        Map<String, String> settings = new HashMap<>();
        // Pin each channel to the disposable broker and give Quarkus only the restricted identity.
        for (String channel : List.of("incoming.tickets", "incoming.orders", "outgoing.triage-results",
                "outgoing.audit-events", "outgoing.published-replies")) {
            String prefix = "mp.messaging." + channel + ".";
            clientSettings(AGENT, agentPassword)
                    .forEach((key, value) -> settings.put(prefix + key, value.toString()));
        }
        settings.put("mp.messaging.incoming.tickets.failure-strategy", "fail");
        return Map.copyOf(settings);
    }

    private void provision() throws ExecutionException, InterruptedException, TimeoutException {
        try (Admin admin = Admin.create(clientSettings(ADMIN, adminPassword))) {
            admin.createTopics(List.of(TICKETS, ORDERS, TRIAGE, PUBLISHED, INTAKE, AgentProtocol.AUDIT_TOPIC).stream()
                    .map(name -> new NewTopic(name, 1, (short) 1)).toList()).all().get(20, TimeUnit.SECONDS);
            admin.createAcls(List.of(
                    allow(AGENT, ResourceType.TOPIC, TICKETS, AclOperation.READ),
                    allow(AGENT, ResourceType.GROUP, GROUP, AclOperation.READ),
                    allow(AGENT, ResourceType.TOPIC, ORDERS, AclOperation.READ),
                    allow(AGENT, ResourceType.GROUP, GROUP + "-orders", AclOperation.READ),
                    allow(AGENT, ResourceType.TOPIC, TRIAGE, AclOperation.WRITE),
                    allow(AGENT, ResourceType.TOPIC, AgentProtocol.AUDIT_TOPIC, AclOperation.WRITE),
                    // Let the agent look up the topic so the broker rejects the write itself.
                    allow(AGENT, ResourceType.TOPIC, PUBLISHED, AclOperation.DESCRIBE),
                    allow(PUBLISHER, ResourceType.TOPIC, PUBLISHED, AclOperation.WRITE),
                    // Identity binding: only the gateway may write tickets; customer apps only reach the intake.
                    allow(GATEWAY, ResourceType.TOPIC, TICKETS, AclOperation.WRITE),
                    allow(CUSTOMER_APP, ResourceType.TOPIC, INTAKE, AclOperation.WRITE),
                    allow(CUSTOMER_APP, ResourceType.TOPIC, TICKETS, AclOperation.DESCRIBE)))
                    .all().get(20, TimeUnit.SECONDS);
        }
    }

    private static AclBinding allow(String user, ResourceType type, String resource, AclOperation operation) {
        return new AclBinding(
                new ResourcePattern(type, resource, PatternType.LITERAL),
                new AccessControlEntry("User:" + user, "*", operation, AclPermissionType.ALLOW));
    }

    private static String jaas(String user, String password) {
        return "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                + user + "\" password=\"" + password + "\";";
    }

    private final class AuthorizationBroker extends GenericContainer<AuthorizationBroker> {

        private static final String START = "/tmp/start-acl-fixture.sh";

        private AuthorizationBroker() {
            super("apache/kafka:4.1.1");
            withExposedPorts(CLIENT_PORT);
            withCreateContainerCmdModifier(command -> {
                Ports ports = new Ports();
                ports.bind(ExposedPort.tcp(CLIENT_PORT), Ports.Binding.bindIp("127.0.0.1"));
                command.getHostConfig().withPortBindings(ports);
            });
            withEnv(Map.ofEntries(
                    Map.entry("KAFKA_PROCESS_ROLES", "broker,controller"),
                    Map.entry("KAFKA_NODE_ID", "1"),
                    Map.entry("KAFKA_LISTENERS",
                            "CLIENT://:9092,BROKER://127.0.0.1:9093,CONTROLLER://127.0.0.1:9094"),
                    Map.entry("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                            "CLIENT:SASL_PLAINTEXT,BROKER:PLAINTEXT,CONTROLLER:PLAINTEXT"),
                    Map.entry("KAFKA_INTER_BROKER_LISTENER_NAME", "BROKER"),
                    Map.entry("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER"),
                    Map.entry("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@127.0.0.1:9094"),
                    Map.entry("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1"),
                    Map.entry("KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS", "1"),
                    Map.entry("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0"),
                    Map.entry("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false"),
                    Map.entry("KAFKA_AUTHORIZER_CLASS_NAME", "org.apache.kafka.metadata.authorizer.StandardAuthorizer"),
                    // ANONYMOUS is only reachable on the container's private loopback listeners.
                    Map.entry("KAFKA_SUPER_USERS", "User:ANONYMOUS;User:" + ADMIN),
                    Map.entry("KAFKA_ALLOW_EVERYONE_IF_NO_ACL_FOUND", "false"),
                    Map.entry("KAFKA_SASL_ENABLED_MECHANISMS", "PLAIN"),
                    Map.entry("KAFKA_LISTENER_NAME_CLIENT_PLAIN_SASL_JAAS_CONFIG",
                            "org.apache.kafka.common.security.plain.PlainLoginModule required"
                                    + " user_" + ADMIN + "=\"" + adminPassword + "\""
                                    + " user_" + AGENT + "=\"" + agentPassword + "\""
                                    + " user_" + PUBLISHER + "=\"" + publisherPassword + "\""
                                    + " user_" + GATEWAY + "=\"" + gatewayPassword + "\""
                                    + " user_" + CUSTOMER_APP + "=\"" + customerAppPassword + "\";")));
            withCommand("sh", "-c", "while [ ! -f " + START + " ]; do sleep 0.1; done; exec sh " + START);
            waitingFor(Wait.forLogMessage(".*Transitioning from RECOVERY to RUNNING.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(60)));
        }

        @Override
        protected void containerIsStarting(InspectContainerResponse containerInfo) {
            copyFileToContainer(Transferable.of("export KAFKA_ADVERTISED_LISTENERS=CLIENT://"
                    + bootstrapServers() + ",BROKER://127.0.0.1:9093\n"
                    + "exec /etc/kafka/docker/run\n"), START);
        }

        private String bootstrapServers() {
            return "127.0.0.1:" + getMappedPort(CLIENT_PORT);
        }
    }
}
