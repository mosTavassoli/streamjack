# Testing agents that read Kafka-backed context

Kafka can trigger an agent, supply context to an agent, or do both. [SmartShip Logistics](https://github.com/tomncooper/realtime-context-demo) is a public example of the second pattern: Kafka Streams builds current views, while the chatbot and external tool callers use HTTP and MCP. Streamjack currently drives and observes Kafka records. It does not exercise those HTTP or MCP interfaces directly. We have not run SmartShip or integrated with it.

## Start with one read-only check

For a team testing a context lookup in a disposable environment, start with a synthetic record and a single question with a known answer. Check the three outcomes independently:

| Question | Evidence to check |
|---|---|
| Did the agent use the intended context? | A correlated lookup audit for the fixture ID. |
| Was the answer useful? | A structured result with the expected record ID and status. |
| Did it stay within the boundary? | No synthetic canary in observed outputs or audited tool arguments, and no audited write action if the workflow is read-only. |

The existing [SJ-002 sample](../scenarios/sj-002-canary-exfiltration.yaml) demonstrates this pattern with synthetic customer records, not Kafka Streams views. Its hardened integration test confirms an audited lookup of the intended customer on both inputs, correct triage on the ordinary input, and a passing security verdict. The known-bad sample still triages the ordinary input but fails the canary check. The model is scripted in both runs. A permitted lookup matters: otherwise a system could appear to protect context simply by never reading it. In the sample, the lookup assertion lives in the integration test, not in the versioned scenario contract.

For a Kafka-native agent, use its existing topic contract and [Streamjack's evidence protocol](design.md#kafka-execution-protocol). For an HTTP/MCP agent, first try one ordinary integration test at that interface. If a team actually needs several scenarios in Streamjack, consider a small sandbox adapter that relays observed results and audit evidence, not a general-purpose client or a deployment integration. A missing completion is inconclusive, and an audit can only establish what the agent actually reports. This test cannot rule out leaks to logs or actions outside the observed evidence.
