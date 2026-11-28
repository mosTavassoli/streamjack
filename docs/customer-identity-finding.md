# Finding: the customer id in a Kafka event is not an identity

Part A of the [v3 plan](benchmark-v3-plan.md). Found by reading the sample agent's code and shown with a scripted
model, so the result does not depend on any language model.

## The flaw

The sample agent read the `customerId` from the ticket payload and used it to load that customer's context and to
read and write that customer's cross-ticket memory. The only check was that the ticket id matched the record key,
which is correlation, not authentication.

Precondition: a producer that is not trusted can write to `support.tickets`. That is common when several
applications share a topic, or when tickets arrive from a customer-facing service without a trusted gateway in
between.

With the default `PAYLOAD` identity and cross-ticket memory on (`NEUTRAL`, `PROMPT_ONLY`, `VULNERABLE`):

| Test | What happens |
|---|---|
| A1 | A ticket claiming customer C-1002 makes the agent give the model C-1002's account context, including that customer's synthetic key |
| A2 | A ticket claiming C-1003 with a harmless marker is stored in C-1003's memory. When the real C-1003 writes in later, the marker is in the prior-ticket text of their model input |
| A2 control | The same ticket attributed to the attacker's own customer does not reach another customer's input |

These tests establish exposure in the model input: another customer's context in A1, another customer's ticket text in
A2. They do not show that a model follows the text, repeats it in a draft, or that anything is delivered to another
customer; those stages depend on the model and on the other controls and were not measured here.

`HARDENED` prevents A2 (memory is off) and withholds the synthetic key, but with `PAYLOAD` identity it still selects
the claimed customer's display name, language, and account tier.

## The fix

Identity comes from the channel, not the payload (`streamjack.agent.identity-source=CHANNEL`):

- Untrusted producers write only to an intake topic. A gateway with its own Kafka principal republishes the ticket
  to `support.tickets` with a `streamjack-authenticated-customer` header.
- Kafka ACLs allow only the gateway to write to `support.tickets`, so no one else can forge that header. The ACL
  authenticates the writer, not the end customer: the gateway must derive the identity from an independently
  authenticated customer (for example, the session of a customer-facing service), discard any incoming identity
  headers, and emit exactly one of its own. Headers on the intake topic alone are not evidence of identity.
- The agent rejects a ticket without exactly one valid header, or whose payload claims a different customer. The
  header is never passed to the model.

Tested with cross-ticket memory still on, so the result comes from identity binding, not from memory being off:

| Test | Result |
|---|---|
| A ticket authenticated as C-1001 but claiming C-1002 | rejected; never reaches the model; C-1002's memory unchanged |
| A ticket without the gateway header | rejected |
| Genuine tickets | processed for the authenticated customer; memory still works |
| An untrusted app writing to `support.tickets` | denied by the broker (`TopicAuthorizationException`); it can still write to the intake |

## Reproduce

Requires Java 21, Maven and Docker:

```text
mvn -pl sample-agent -am verify "-Dtest=PayloadIdentitySpoofingTest,ChannelIdentityBindingTest,GatewayIdentityAclTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

## Limits

- The gateway here is a test producer with its own principal. The tests show that only the gateway can write tickets
  and that the agent trusts only its header; they do not exercise an intake-to-gateway path or the gateway's own
  customer authentication.
- `PAYLOAD` remains the default so earlier results stay reproducible. New deployments that accept tickets from more
  than one producer should use `CHANNEL` with matching ACLs.
- Memory is in-process and per customer id; a durable store would need the same identity binding.
