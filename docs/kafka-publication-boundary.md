# Kafka enforces the publication boundary

The ticket scenarios show how Streamjack **detects** an unsafe action. This separate integration test asks whether Kafka can **prevent** that action even when the agent decides to publish.

[Cline's February 2026 post-mortem](https://cline.bot/blog/post-mortem-unauthorized-cline-cli-npm) describes how an AI triage workflow that processed untrusted issue text was not isolated from release infrastructure. Kafka was not involved in that incident. The lesson for this test is to keep the triage agent's identity separate from the identity that can publish.

## What the test checks

| Step | Evidence |
|---|---|
| An ordinary billing ticket goes to the triage agent. | The test reads a draft with `HUMAN_REVIEW` on `support.triage` and a successful completion audit. |
| A second synthetic ticket gets a preset `PUBLISH` recommendation in a test-only model fixture. | The real agent producer receives Kafka's `TopicAuthorizationException` for `support.published`. The agent audits the event as failed, and the test observes zero records on that topic before the next step. |
| A separate, authorized test producer sends a simulated reply. | Kafka acknowledges the write and the test reads the matching record from `support.published`. The topic was available; the restriction was on the agent's identity. |

The broker authenticates each client and enforces its access control lists (ACLs). The triage identity can read `support.tickets` with its consumer group and write to `support.triage` and `streamjack.audit`. It can discover `support.published` but cannot write there. A fixture admin creates the topics and ACLs; the separate test publisher has write access to `support.published`. Neither privileged credential is passed to the agent's configuration or model.

## Run it

From the repository root with Java 21, Maven, and a local Docker engine running Linux containers:

```sh
mvn --batch-mode --no-transfer-progress -pl sample-agent -am test "-Dtest=KafkaPublicationAuthorizationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

The test starts and stops its own Kafka 4.1.1 container with a loopback-only client port. It generates disposable SASL/PLAIN credentials and uses ordinary synthetic ticket text and a scripted model. The unencrypted listener is for this local fixture only, not a deployment template. It does not use a real model, external credentials, or a live Kafka cluster. All replies are simulated Kafka records. This is also part of `mvn verify`.

## What this does not prove

- This is a test of broker permissions, not a reproduction of the Cline compromise or a test of whether a model can be tricked.
- The test publisher is a positive control, not a deployed publisher or an implementation of human approval. The test clients and agent run in the same test JVM; the test does not establish process isolation.
- The normal ticket's draft and the rejected publication are separate cases. The deliberately permissive test profile does not implement a fallback to human review for a rejected publication.
- The test does not change the scenario contract or report format. It checks an explicit Kafka client error outside the scenario runner. The runner still treats failed event completions as `INCONCLUSIVE`.
