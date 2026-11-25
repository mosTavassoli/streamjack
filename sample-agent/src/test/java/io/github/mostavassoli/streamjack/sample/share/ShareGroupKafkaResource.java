package io.github.mostavassoli.streamjack.sample.share;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import io.github.mostavassoli.streamjack.core.protocol.AgentProtocol;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.ConfigResource;
import org.testcontainers.kafka.KafkaContainer;

/** Disposable Kafka 4.2 broker with a short share-group record lock for the sample agent's ticket group. */
public final class ShareGroupKafkaResource implements QuarkusTestResourceLifecycleManager {

    static final String GROUP = "streamjack-sample-agent";

    private KafkaContainer kafka;
    private String lockMs = "2000";

    @Override
    public void init(Map<String, String> initArgs) {
        lockMs = initArgs.getOrDefault("lockMs", lockMs);
    }

    @Override
    public Map<String, String> start() {
        kafka = new KafkaContainer("apache/kafka:4.2.1")
                .withEnv("KAFKA_GROUP_SHARE_MIN_RECORD_LOCK_DURATION_MS", "1000")
                .withEnv("KAFKA_SHARE_COORDINATOR_STATE_TOPIC_REPLICATION_FACTOR", "1")
                .withEnv("KAFKA_SHARE_COORDINATOR_STATE_TOPIC_MIN_ISR", "1")
                // The listener is unauthenticated, so publish it on loopback only.
                .withCreateContainerCmdModifier(command -> {
                    Ports ports = new Ports();
                    ports.bind(ExposedPort.tcp(9092), Ports.Binding.bindIp("127.0.0.1"));
                    command.getHostConfig().withPortBindings(ports);
                });
        kafka.start();
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of("support.tickets", "support.triage", "support.published", AgentProtocol.AUDIT_TOPIC)
                    .stream().map(name -> new NewTopic(name, 1, (short) 1)).toList()).all().get(20, TimeUnit.SECONDS);
            admin.incrementalAlterConfigs(Map.of(new ConfigResource(ConfigResource.Type.GROUP, GROUP), List.of(
                            set("share.auto.offset.reset", "earliest"),
                            set("share.record.lock.duration.ms", lockMs))))
                    .all().get(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not prepare the share-group broker", e);
        }
        return Map.of("kafka.bootstrap.servers", kafka.getBootstrapServers());
    }

    @Override
    public void stop() {
        if (kafka != null) {
            kafka.stop();
        }
    }

    private static AlterConfigOp set(String name, String value) {
        return new AlterConfigOp(new ConfigEntry(name, value), AlterConfigOp.OpType.SET);
    }
}
