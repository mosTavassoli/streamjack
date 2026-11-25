package io.github.mostavassoli.streamjack.kafka;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.IsolationLevel;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;

/**
 * The set of partitions the runner observes, and whether everything written to them during the run has been read.
 *
 * <p>Partitions are assigned directly rather than through a consumer group: a rebalance would reset partitions
 * without committed offsets to the earliest offset, so old records would be read again as new evidence. Internal
 * topics are identified by the broker, not by name, because any client can create a topic whose name starts with
 * {@code __}. Topics are tracked by id, so a topic deleted or recreated during the run is detected rather than
 * mistaken for the original.
 */
final class ObservedPartitions {

    /** A Kafka metadata or offset request did not complete; the caller cannot confirm the state yet. */
    static final class Unconfirmed extends Exception {

        private static final long serialVersionUID = 1L;

        Unconfirmed(Throwable cause) {
            super(cause);
        }
    }

    /** Evidence may have been lost, so the run cannot be evaluated. */
    static final class CoverageLost extends Exception {

        private static final long serialVersionUID = 1L;

        CoverageLost(String message) {
            super(message);
        }
    }

    private final Admin admin;
    private final KafkaConsumer<String, String> consumer;
    private final Map<String, Uuid> topicIds = new HashMap<>();
    // End of each partition, including records behind open transactions, when observation of it started.
    private final Map<TopicPartition, Long> baseline = new HashMap<>();

    ObservedPartitions(Admin admin, KafkaConsumer<String, String> consumer) {
        this.admin = admin;
        this.consumer = consumer;
    }

    /** Observes every existing non-internal partition, starting at its current end. */
    void assignAllFromEnd(Duration timeout) throws Unconfirmed {
        long deadline = deadline(timeout);
        Map<String, TopicDescription> topics = describeAll(deadline);
        Set<TopicPartition> partitions = partitionsOf(topics);
        if (partitions.isEmpty()) {
            throw new Unconfirmed(new IllegalStateException("no topics to observe"));
        }
        Map<TopicPartition, Long> ends = latest(partitions, deadline);
        topicIds.clear();
        topics.forEach((name, description) -> topicIds.put(name, description.topicId()));
        baseline.clear();
        baseline.putAll(ends);
        consumer.assign(partitions);
        // Start exactly at the recorded baseline, so nothing between the baseline and the start is skipped.
        ends.forEach(consumer::seek);
    }

    /**
     * Starts observing partitions that appeared since the last call (for example, a topic the agent auto-created
     * by writing to a disallowed topic) from their beginning. Returns whether any were added.
     */
    boolean addNewFromBeginning(Duration timeout) throws Unconfirmed, CoverageLost {
        Map<String, TopicDescription> topics = describeAll(deadline(timeout));
        for (Map.Entry<String, Uuid> known : topicIds.entrySet()) {
            TopicDescription current = topics.get(known.getKey());
            if (current == null) {
                throw new CoverageLost("Observed topic " + known.getKey() + " was deleted during the run");
            }
            if (!current.topicId().equals(known.getValue())) {
                throw new CoverageLost("Observed topic " + known.getKey() + " was recreated during the run");
            }
        }
        Set<TopicPartition> added = partitionsOf(topics);
        added.removeAll(consumer.assignment());
        if (added.isEmpty()) {
            return false;
        }
        topics.forEach((name, description) -> topicIds.putIfAbsent(name, description.topicId()));
        Set<TopicPartition> all = new HashSet<>(consumer.assignment());
        all.addAll(added);
        consumer.assign(all);
        consumer.seekToBeginning(added);
        // Everything in a partition created during observation was written during the run.
        added.forEach(partition -> baseline.put(partition, 0L));
        return true;
    }

    /**
     * Targets for "everything written during the run has been read": the current end of each partition that grew
     * since observation of it started, including records behind open transactions. The observer reads only
     * committed data, so it reaches a target only after every transaction before it has finished; until then, a
     * record written after an open transaction is unreadable and the run cannot be confirmed. Partitions nobody
     * wrote to during the run are left out, so an unrelated, long-open transaction does not block the run.
     */
    Map<TopicPartition, Long> drainTargets(Duration timeout) throws Unconfirmed {
        Map<TopicPartition, Long> targets = new HashMap<>();
        latest(consumer.assignment(), deadline(timeout)).forEach((partition, end) -> {
            if (end > baseline.getOrDefault(partition, 0L)) {
                targets.put(partition, end);
            }
        });
        return targets;
    }

    /** Whether the observer has read every partition in {@code targets} up to its target offset. */
    boolean reached(Map<TopicPartition, Long> targets, Duration timeout) throws Unconfirmed {
        long deadline = deadline(timeout);
        try {
            for (Map.Entry<TopicPartition, Long> target : targets.entrySet()) {
                if (consumer.position(target.getKey(), remaining(deadline)) < target.getValue()) {
                    return false;
                }
            }
            return true;
        } catch (org.apache.kafka.common.errors.TimeoutException e) {
            throw new Unconfirmed(e);
        }
    }

    /**
     * Describes every topic the broker reports as non-internal. Fails unless every listed topic was described, so
     * a topic that is still being created is retried instead of silently left out.
     */
    private Map<String, TopicDescription> describeAll(long deadline) throws Unconfirmed {
        Set<String> names = await(
                admin.listTopics(new ListTopicsOptions().listInternal(false)).names(), deadline);
        Map<String, TopicDescription> topics = new HashMap<>();
        for (Map.Entry<String, KafkaFuture<TopicDescription>> topic
                : admin.describeTopics(names).topicNameValues().entrySet()) {
            TopicDescription description = await(topic.getValue(), deadline);
            if (!description.isInternal()) {
                topics.put(topic.getKey(), description);
            }
        }
        return topics;
    }

    private static Set<TopicPartition> partitionsOf(Map<String, TopicDescription> topics) {
        Set<TopicPartition> partitions = new HashSet<>();
        topics.forEach((name, description) ->
                description.partitions().forEach(info -> partitions.add(new TopicPartition(name, info.partition()))));
        return partitions;
    }

    private Map<TopicPartition, Long> latest(Set<TopicPartition> partitions, long deadline) throws Unconfirmed {
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        partitions.forEach(partition -> request.put(partition, OffsetSpec.latest()));
        var offsets = await(admin.listOffsets(
                request, new ListOffsetsOptions(IsolationLevel.READ_UNCOMMITTED)).all(), deadline);
        Map<TopicPartition, Long> ends = new HashMap<>();
        offsets.forEach((partition, info) -> ends.put(partition, info.offset()));
        return ends;
    }

    private static long deadline(Duration timeout) {
        return System.nanoTime() + timeout.toNanos();
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    }

    private static <T> T await(KafkaFuture<T> future, long deadline) throws Unconfirmed {
        long left = deadline - System.nanoTime();
        try {
            if (left <= 0) {
                // No time left: use a completed result, but never wait past the deadline.
                if (!future.isDone()) {
                    throw new Unconfirmed(new TimeoutException("Kafka metadata deadline expired"));
                }
                return future.get();
            }
            return future.get(left, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaScenarioRunner.KafkaRunnerException("Interrupted while reading Kafka metadata", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new Unconfirmed(e);
        }
    }
}
