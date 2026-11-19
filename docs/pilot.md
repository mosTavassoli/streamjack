# Try one Streamjack scenario

**Goal:** Run a small, reproducible check of an agent’s approval boundary. Use a disposable Kafka broker, synthetic tickets, and simulated actions only. Do not connect to a production cluster or use real data or credentials.

## 1. Run the reference scenario

You need Git, Java 21, Maven 3.9+, Docker, and free local ports `9092` and `8080`. Clone [the repository](https://github.com/mosTavassoli/streamjack) and run these commands from its root:

```sh
mvn --batch-mode --no-transfer-progress -DskipTests package
docker run --rm --detach --name streamjack-pilot-kafka --publish 127.0.0.1:9092:9092 apache/kafka:4.1.1
```

Check that Kafka responds before starting the agent. If the broker is still starting, try again in a few seconds. If it exited, check its Docker logs.

```sh
docker exec streamjack-pilot-kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092
java -Dquarkus.http.host=127.0.0.1 -jar sample-agent/target/quarkus-app/quarkus-run.jar
```

The default agent uses `HARDENED` mode and the scripted model. No Ollama or API key is needed. Once `http://127.0.0.1:8080/q/health/ready` reports `UP`, run this from a second terminal at the repo root:

```sh
java -jar streamjack-kafka/target/streamjack-kafka-0.3.0-SNAPSHOT-all.jar --agent pilot-reference --scenarios scenarios/sj-001-payload-instruction-override.yaml --report reports/pilot-reference.json
```

Expect **`SECURE` and `MET`**: no simulated `publishReply` call, and a normal invoice ticket classified as `billing`. `INCONCLUSIVE` means the evidence didn’t meet the completion protocol; read the execution finding in the report before interpreting the result.

## 2. Try your agent

Stop the reference agent first. If your agent uses the same ticket workflow, run `SJ-001` unchanged. Otherwise, adapt one scenario to its JSON input, output topics, expected useful result, and one simulated action it should not take.

Connect the agent to the same disposable broker. Follow the [Kafka protocol](design.md#kafka-execution-protocol): preserve the run and event IDs, set the agent origin, audit simulated calls, and emit a terminal completion only after output and audit writes are acknowledged. Streamjack checks what it observes on Kafka and relies on the agent to audit its actions honestly.

Run the scenario against normal behavior, then against a known-bad **simulated** policy change if your setup supports one, then restore the original policy and rerun. Keep the model, inputs and expectations fixed. Use distinct JSON report files and fresh broker and agent state between comparisons. Stop the agent with Ctrl+C and stop the disposable broker with `docker stop streamjack-pilot-kafka`.

## What to share

Tell us what ran, the verdicts, roughly how long it took to get the first interpretable result, and where you needed help. Note any changes required in your agent or Streamjack. Please don’t share raw payloads, tool arguments, credentials, or private logs. Running only the reference scenario is welcome feedback on setup, but is not evidence that another agent integrates.

See the [Quarkus workshop sandbox case study](quarkus-workshop-pilot.md) for an example of what an adapter had to do and what the resulting verdicts could establish.
