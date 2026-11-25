# Slow agents and Kafka share-group redelivery

Kafka share groups ([KIP-932](https://cwiki.apache.org/confluence/display/KAFKA/KIP-932%3A+Queues+for+Kafka)) let several consumers take records from the same partitions, like a queue. Each delivered record is locked to one consumer for a limited time (30 s by default). If the consumer does not acknowledge the record before the lock expires, Kafka delivers it again, up to a delivery limit (5 by default).

An AI agent can take longer than the lock to handle one event: a model call, tool calls, retries. When that happens, the same event is processed again, and any side effect it triggers can repeat. This is SJ-004's duplicate-delivery threat, caused by latency instead of a rebalance.

Share groups are a preview feature in Kafka 4.1 and enabled by default from Kafka 4.2. Everything here runs against a disposable local broker; no model, credentials, or external systems are used.

## What the plain Kafka client does

[`ShareGroupRedeliveryIT`](../streamjack-kafka/src/test/java/io/github/mostavassoli/streamjack/kafka/ShareGroupRedeliveryIT.java) runs on Kafka 4.2.1 as part of `mvn verify`. Two simulated agent instances share one group; each record takes 3 s of "thinking" and then triggers one side effect. Timings are scaled down from a manual run with a 30 s lock and 40 s of processing.

| Case | Lock | Deliveries | Side effects |
|---|---|---|---|
| Processing blocks the consumer thread | 2 s | 5 (the delivery limit) | 5 |
| Same, with deduplication of the side effect | 2 s | 5 | 1 |
| Lock longer than processing | 6 s | 1 | 1 |
| Lock renewed from a free consumer thread while working (Kafka 4.2+) | 3 s, with 4.5 s processing | 1 | 1 |

Each case asserts that processing settled before the deadline, both instances stopped, and, where the record is delivered once, that exactly one acknowledgement succeeded.

What this shows:

- **Deduplication prevents repeated side effects, not repeated work.** The model still runs five times.
- **A longer lock works if you know the worst-case processing time.** The broker caps it (`group.share.max.record.lock.duration.ms`, 60 s by default).
- **Renewal works with the plain client** if the thread that polls Kafka stays free to renew while another thread does the work. A consumer that blocks while working cannot renew.

## What a framework does: SmallRye Reactive Messaging

Frameworks are expected to renew the lock for you. The opt-in experiments in [`sample-agent/src/test/.../share/`](../sample-agent/src/test/java/io/github/mostavassoli/streamjack/sample/share/) run the sample agent with a slow scripted model on Quarkus 3.40 and SmallRye Reactive Messaging 4.37:

```bash
mvn -pl sample-agent -am test -Dstreamjack.experiments=true \
  -Dtest='SmallRyeShareGroup*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

Each experiment reports how many analyses started and completed, and whether processing settled before the time limit. Observed with processing slower than the lock:

- The lock is not kept. The record is delivered again with an increasing delivery count, and the handler runs 1 to 5 times; the calls do not overlap.
- After the first renewal failure, renewal for that record fails at every interval (more than 100 failures per run).
- A later record is acknowledged by the broker but never reaches the handler.

The same behavior reproduces on Quarkus 4.0.0.CR1 with SmallRye 5.0.0-RC2 in a [minimal reproducer](https://github.com/mosTavassoli/smallrye-share-group-renewal-repro), including on GitHub Actions. It is reported upstream as [smallrye-reactive-messaging#3636](https://github.com/smallrye/smallrye-reactive-messaging/issues/3636). The root cause is not confirmed.

The problem follows renewal itself rather than slow processing: with a 2 s lock, a fast (0.5 s) handler was also redelivered in some runs, because renewal is scheduled from when the connector received the record. The control experiment therefore uses a 10 s lock, where renewal never starts, and asserts that each record is handled exactly once. With default settings, renewal starts about 25 s after a record arrives, so in practice it mainly affects slow handlers such as AI agents.

## Guidance

1. **Make side effects idempotent in application code.** This is the only control that held in every case, including a framework bug. Streamjack's hardened agent deduplicates follow-ups by ticket id, and SJ-004 requires exactly one.
2. **Size the lock for worst-case processing** where you can, within the broker's maximum.
3. **If you rely on renewal, test it** with a handler slower than the lock, and check delivery counts and that later records are still processed. Renewal is off by default in some frameworks (for example, Spring Kafka's `setShareAutoRenewLocks`), and in SmallRye it currently does not keep the lock (#3636).
