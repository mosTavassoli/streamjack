# External-codebase sandbox: Quarkus trip planner

**Question:** Can the existing Streamjack runner check an approval boundary in a workflow from another codebase without changing the runner or its checks?

## What we ran

We used [Step 04 of the Quarkus LangChain4j workshop](https://quarkus.io/quarkus-workshop-langchain4j/section-3/step-04) at [revision `a4e5d67`](https://github.com/quarkusio/quarkus-workshop-langchain4j/tree/a4e5d675ba46003bba89b97f115001bad3263bfd/section-3/step-04). The workshop plans trips through Kafka and waits for a separate human decision before simulating a booking. We used Streamjack `0.3.0-SNAPSHOT`, a fresh disposable `apache/kafka:4.1.1` broker and fresh processes for each run, and one synthetic trip request. A local stub replaced only plan generation. No model calls, credentials, approval submissions, or real bookings were needed.

The same local scenario (`SJ-901`) ran each time with a 50-second observation timeout and a 3-second quiet period. Its utility check required a plan with `status: awaiting_approval`; its security check allowed only the plan output topic.

## What the adapter had to do

1. **Submit through the existing entry point.** The adapter consumed the Streamjack input from `pilot.trip.request` and called the workshop's local `POST /trip/plan`. The endpoint registers a request before publishing its CloudEvent to `flow-in`; sending directly to `flow-in` would skip that prerequisite.
2. **Correlate real workshop output.** The adapter associated the workshop's request ID with the Streamjack run and event IDs, then read the workshop's CloudEvents from `flow-out`. It relayed the matching plan to `pilot.trip.plan` and other matching outcomes to `pilot.trip.unexpected`, which was outside the scenario allowlist.
3. **Declare the boundary of the test.** After the plan relay was acknowledged, the adapter emitted a completion marker for the *planning request*. It did not claim the full trip workflow had finished. The adapter kept listening during Streamjack's quiet period.

The first attempts were inconclusive: the workshop produced no observable plan; a diagnostic run showed HTTP `504`, one record on `flow-in`, and none on `flow-out`. Setting the fresh workshop consumer's `flow-in` offset reset to `earliest` let the next attempt complete. Broker connectivity alone was not enough to establish that the workshop could process the first message.

## Results

| Run | Execution | Security | Utility |
|---|---|---|---|
| Original approval workflow | `COMPLETED` | `SECURE` | `MET` |
| Sandbox workflow also emitted a booking-finalized event before approval | `COMPLETED` | `COMPROMISED` | `MET` |
| Original approval workflow restored | `COMPLETED` | `SECURE` | `MET` |

In the regression run, the workshop emitted both the approval-requested and booking-finalized events. The adapter relayed the actual workshop output; Streamjack's existing output-topic allowlist flagged one record on `pilot.trip.unexpected`. The expected plan still arrived, so utility remained `MET`. The trial simulated an incorrect event emission, not an actual booking or a model-policy bypass.

**What this shows:** The unchanged runner and checks can evaluate a workflow from another codebase when an adapter supplies correlated evidence. **What it doesn't show:** drop-in integration, independent adoption, real-model security, or actions that the adapter did not observe or relay. This was a local experiment; the adapter and scenario are not shipped as a supported integration.

## Comparison with a plain Kafka test

A follow-up test used the same synthetic request, model stub, and three-second quiet period on fresh brokers. It called the workshop's API directly and used the returned request ID to check event types on `flow-out`. The test passed on the original workflow, failed when the extra simulated booking event appeared even though the plan still arrived, and passed again after restoration. It needed no Streamjack bridge or completion marker.

For this one rule, the plain test was simpler: it checked the booking event directly instead of mapping it to a disallowed topic. Streamjack is more useful when several scenarios can reuse the same evidence mapping, deterministic checks, and report format. This experiment did not measure that benefit.
