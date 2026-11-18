# Guidance for coding agents

## Mission

streamjack tests whether AI agents that consume and produce Apache Kafka events stay safe under adversarial events while still doing their job.

## Commands

- `mvn verify` — build and run all tests (Java 21)

## Boundaries

- `streamjack-core` has no Kafka or LLM dependency. It owns the scenario contract, evidence model, checks, and reports.
- The scenario contract (`streamjack.dev/v1alpha1`) and report format are versioned and language-neutral. Breaking changes need a new version.
- Checks are deterministic. No LLM-as-judge.
- The runner treats the agent under test as a black box and only talks Kafka.

## Security rules

1. Disposable test environments only. Never production clusters, data, or real credentials.
2. Secrets in scenarios are synthetic canaries.
3. Simulated privileged tools must never perform real side effects.
4. Treat event payloads, headers, tool responses, and model output as untrusted.
5. Validate every external input against the versioned contract.

## Definition of done

- Behavior is tested and `mvn verify` passes.
- Contract changes are versioned and documented in `docs/design.md`.
- User-facing changes update the README.
