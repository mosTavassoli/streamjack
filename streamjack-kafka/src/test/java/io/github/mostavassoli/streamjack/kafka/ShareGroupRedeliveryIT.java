package io.github.mostavassoli.streamjack.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.clients.consumer.AcknowledgeType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaShareConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Characterizes what Kafka share groups (KIP-932) do to an agent whose processing outlasts the record lock.
 *
 * <p>Two simulated agent instances share one group. Each "thinks" for {@link #WORK} (standing in for a slow model call)
 * and then performs one side effect per record. Timings are scaled down from the manual experiment (30 s lock, 40 s
 * processing) with the same ratio, so each case runs in seconds. No model, credentials, or external systems are used.
 *
 * <p>A consumer that blocks while working cannot renew its lock, so Kafka redelivers the record up to the delivery
 * limit. Deduplication then prevents repeated side effects but not repeated work. A lock longer than the processing
 * time, or renewing the lock from a responsive consumer thread (Kafka 4.2+), delivers the record once.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShareGroupRedeliveryIT {

    private static final Duration WORK = Duration.ofSeconds(3);
    private static final int DEFAULT_DELIVERY_LIMIT = 5;

    // Kafka 4.2 is the first release where share groups are enabled by default and the client can renew a lock.
    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1")
            // Allow a short group lock on this disposable broker so the test stays fast.
            .withEnv("KAFKA_GROUP_SHARE_MIN_RECORD_LOCK_DURATION_MS", "1000")
            // A single broker cannot host the share-group state topic at the default replication factor of 3.
            .withEnv("KAFKA_SHARE_COORDINATOR_STATE_TOPIC_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_SHARE_COORDINATOR_STATE_TOPIC_MIN_ISR", "1")
            // The listener is unauthenticated, so publish it on loopback only.
            .withCreateContainerCmdModifier(command -> {
                Ports ports = new Ports();
                ports.bind(ExposedPort.tcp(9092), Ports.Binding.bindIp("127.0.0.1"));
                command.getHostConfig().withPortBindings(ports);
            });

    @Test
    void slowAgentRepeatsTheSideEffectUntilTheDeliveryLimit() throws Exception {
        Outcome outcome = run("no-fix", Duration.ofSeconds(2), WORK, false, false);

        assertThat(outcome.deliveries()).containsExactly(1, 2, 3, 4, 5);
        assertThat(outcome.sideEffects()).isEqualTo(DEFAULT_DELIVERY_LIMIT);
        assertThat(outcome.rejectedAcknowledgements()).isPositive();
    }

    @Test
    void deduplicationKeepsOneSideEffectButKafkaStillRedelivers() throws Exception {
        Outcome outcome = run("dedup", Duration.ofSeconds(2), WORK, true, false);

        assertThat(outcome.sideEffects()).isEqualTo(1);
        // Every redelivery still pays for the full slow processing before the duplicate is detected.
        assertThat(outcome.deliveries()).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void lockLongerThanProcessingDeliversOnce() throws Exception {
        Outcome outcome = run("long-lock", Duration.ofSeconds(6), WORK, false, false);

        assertThat(outcome.deliveries()).containsExactly(1);
        assertThat(outcome.sideEffects()).isEqualTo(1);
        assertThat(outcome.rejectedAcknowledgements()).isZero();
        assertThat(outcome.acceptedAcknowledgements()).isEqualTo(1);
    }

    @Test
    void renewingTheLockWhileWorkingDeliversOnceAtTheShortLock() throws Exception {
        // Work outlasts the lock by 1.5 s; renewing every quarter of the lock leaves a wide margin on slow machines.
        Outcome outcome = run("renew", Duration.ofSeconds(3), Duration.ofMillis(4500), false, true);

        assertThat(outcome.deliveries()).containsExactly(1);
        assertThat(outcome.sideEffects()).isEqualTo(1);
        assertThat(outcome.rejectedAcknowledgements()).isZero();
        assertThat(outcome.acceptedAcknowledgements()).isEqualTo(1);
        assertThat(outcome.renewals()).isPositive();
        // Renewing keeps the original delivery attempt; it does not count as a redelivery.
        assertThat(outcome.renewedDeliveryCounts()).isNotEmpty().containsOnly(1);
    }

    private record Outcome(List<Integer> deliveries, int sideEffects, int rejectedAcknowledgements,
            int acceptedAcknowledgements, int renewals, List<Integer> renewedDeliveryCounts) {
    }

    /**
     * @param renew if true, the slow work runs on another thread while the consumer thread keeps renewing the lock;
     *              otherwise the work blocks the consumer thread, as in a simple poll loop.
     */
    private static Outcome run(String name, Duration lock, Duration work, boolean deduplicate, boolean renew)
            throws Exception {
        String topic = "tickets-" + name;
        String group = "agents-" + name;
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
            ConfigResource groupConfig = new ConfigResource(ConfigResource.Type.GROUP, group);
            admin.incrementalAlterConfigs(Map.of(groupConfig, List.of(
                            set("share.auto.offset.reset", "earliest"),
                            set("share.record.lock.duration.ms", Long.toString(lock.toMillis())))))
                    .all().get(20, TimeUnit.SECONDS);
        }

        List<Integer> deliveries = new CopyOnWriteArrayList<>();
        AtomicInteger sideEffects = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger renewals = new AtomicInteger();
        List<Integer> renewedDeliveryCounts = new CopyOnWriteArrayList<>();
        AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        AtomicBoolean running = new AtomicBoolean(true);
        Set<String> completedTickets = ConcurrentHashMap.newKeySet();
        List<RuntimeException> agentErrors = new CopyOnWriteArrayList<>();

        Runnable agent = () -> {
            try (KafkaShareConsumer<String, String> consumer = new KafkaShareConsumer<>(shareConsumerProperties(group))) {
                consumer.subscribe(List.of(topic));
                while (running.get()) {
                    for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                        deliveries.add(record.deliveryCount().map(Short::intValue).orElse(-1));
                        lastActivity.set(System.nanoTime());
                        ConsumerRecord<String, String> current = record;
                        if (renew) {
                            // KafkaShareConsumer is not thread-safe, so only this thread talks to Kafka.
                            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> slowWork(work));
                            while (!task.isDone()) {
                                Thread.sleep(lock.toMillis() / 4);
                                if (task.isDone()) {
                                    break;
                                }
                                consumer.acknowledge(current, AcknowledgeType.RENEW);
                                for (Optional<?> error : consumer.commitSync().values()) {
                                    if (error.isPresent()) {
                                        rejected.incrementAndGet();
                                    }
                                }
                                renewals.incrementAndGet();
                                // A renewed record is handed back by a later poll; acknowledge that copy from now on.
                                current = pollFor(consumer, current, renewedDeliveryCounts);
                            }
                            task.join();
                        } else {
                            slowWork(work);
                        }
                        if (!deduplicate || completedTickets.add(record.key())) {
                            sideEffects.incrementAndGet();
                        }
                        consumer.acknowledge(current, AcknowledgeType.ACCEPT);
                        boolean acceptFailed = false;
                        for (Optional<?> error : consumer.commitSync().values()) {
                            if (error.isPresent()) {
                                rejected.incrementAndGet();
                                acceptFailed = true;
                            }
                        }
                        if (!acceptFailed) {
                            accepted.incrementAndGet();
                        }
                        lastActivity.set(System.nanoTime());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                agentErrors.add(e);
            }
        };
        List<Thread> instances = List.of(new Thread(agent, name + "-A"), new Thread(agent, name + "-B"));
        instances.forEach(thread -> {
            thread.setDaemon(true);
            thread.start();
        });
        Thread.sleep(3000); // let both members join before the ticket arrives

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties())) {
            producer.send(new ProducerRecord<>(topic, "T-7001", "{\"ticketId\":\"T-7001\"}")).get(20, TimeUnit.SECONDS);
        }

        // Done when something was delivered and nothing has happened for longer than a lock plus the processing time.
        long quietNanos = lock.plus(work).plusSeconds(2).toNanos();
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        boolean settled = false;
        while (System.nanoTime() < deadline) {
            if (!deliveries.isEmpty() && System.nanoTime() - lastActivity.get() >= quietNanos) {
                settled = true;
                break;
            }
            Thread.sleep(200);
        }
        running.set(false);
        for (Thread thread : instances) {
            thread.join(work.plusSeconds(10).toMillis());
        }
        assertThat(agentErrors).as("errors in the simulated agent instances").isEmpty();
        assertThat(deliveries).as("the ticket must reach an agent instance").isNotEmpty();
        // Never assert on an unfinished run: it must have gone quiet and both instances must have stopped.
        assertThat(settled).as("processing settled before the deadline").isTrue();
        assertThat(instances).as("agent instances stopped").noneMatch(Thread::isAlive);
        return new Outcome(List.copyOf(deliveries).stream().sorted().toList(), sideEffects.get(), rejected.get(),
                accepted.get(), renewals.get(), List.copyOf(renewedDeliveryCounts));
    }

    /** Polls until the renewed record comes back and returns that copy. */
    private static ConsumerRecord<String, String> pollFor(KafkaShareConsumer<String, String> consumer,
            ConsumerRecord<String, String> renewed, List<Integer> renewedDeliveryCounts) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> copy : consumer.poll(Duration.ofMillis(100))) {
                if (copy.offset() == renewed.offset()) {
                    renewedDeliveryCounts.add(copy.deliveryCount().map(Short::intValue).orElse(-1));
                    return copy;
                }
            }
        }
        throw new IllegalStateException("renewed record " + renewed.offset() + " was not returned by poll");
    }

    /** Stands in for a slow model call. */
    private static void slowWork(Duration work) {
        try {
            Thread.sleep(work.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static Properties shareConsumerProperties(String group) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("group.id", group);
        properties.put("key.deserializer", StringDeserializer.class.getName());
        properties.put("value.deserializer", StringDeserializer.class.getName());
        properties.put("share.acknowledgement.mode", "explicit");
        return properties;
    }

    private static Properties producerProperties() {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("key.serializer", StringSerializer.class.getName());
        properties.put("value.serializer", StringSerializer.class.getName());
        return properties;
    }

    private static Admin admin() {
        return Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()));
    }

    private static AlterConfigOp set(String name, String value) {
        return new AlterConfigOp(new ConfigEntry(name, value), AlterConfigOp.OpType.SET);
    }
}
