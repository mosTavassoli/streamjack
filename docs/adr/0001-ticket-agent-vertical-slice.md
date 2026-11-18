# ADR-0001: One ticket-agent vertical slice for v0.2

- **Status:** Accepted
- **Date:** 2026-11-18

## Context

streamjack needs enough realism to teach event-driven agent safety without becoming a ticketing platform, a Kafka operations product, or a collection of shallow demos. The previous refund example made tool misuse easy to describe but did not represent the approval boundary in the chosen support workflow.

Zammad-AI is a useful business-domain reference: ticket text is classified and summarized, and generated responses support a human workflow. Direct integration would add unrelated APIs, deployment concerns, and credentials before the Kafka harness itself is proven.

CI also needs a stable oracle. A live model cannot be the required execution path because its output varies and may require network access.

## Decision

v0.2 implements one end-to-end workflow:

```text
ticket event → Kafka → Quarkus agent → classify and draft
             → audited simulated actions → deterministic streamjack report
```

The key privileged action is `publishReply`. In hardened mode, model output can recommend an action but application policy always keeps the response in `HUMAN_REVIEW`. Publication is simulated as a Kafka record and never reaches an external system.

The sample has two independent switches:

- deterministic `SCRIPTED` or optional local `OLLAMA` model execution,
- `HARDENED` behavior or a deliberately `VULNERABLE` positive control.

Deterministic CI runs both security modes with the scripted model. Hardened mode must pass all scenarios; vulnerable mode must be detected by all scenarios.

The runner uses explicit correlation headers and terminal completion events. Missing or failed completion is inconclusive rather than secure.

## Consequences

### Positive

- The repository demonstrates a complete, testable Kafka workflow rather than disconnected checks.
- Publication authority is enforced in code instead of delegated to a prompt.
- Testcontainers and scripted inference keep CI reproducible and offline from model services.
- Optional Ollama runs exercise the real LangChain4j integration without weakening the CI oracle.
- The vulnerable mode proves the harness can detect failures instead of merely producing green reports.

### Trade-offs

- The in-memory customer, memory, and idempotency stores are illustrative and not durable.
- The sample does not prove compatibility with Zammad or another ticket platform.
- The audit protocol assumes cooperative instrumentation; cryptographic attestation is out of scope.
- Only one business workflow is covered. New domains should wait until this slice is stable.

## Rejected alternatives

- **Integrate Zammad first:** too much platform scope before validating the harness.
- **Keep refunds as the privileged action:** less representative of ticket-drafting approval.
- **Use a live LLM in CI:** nondeterministic and network-dependent.
- **Use an LLM judge:** violates the deterministic evaluation requirement.
- **Add several sample agents:** broadens maintenance without improving the core evidence model.
