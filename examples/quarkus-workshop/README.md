# Run the Quarkus workshop pilot

This example tests the approval boundary in the [Quarkus LangChain4j workshop trip planner](https://quarkus.io/quarkus-workshop-langchain4j/section-3/step-04), using the unchanged Streamjack runner and one small adapter. It uses synthetic trips, a fresh local Kafka broker for each case, and a fixed plan instead of a model. No real booking is made.

## Run from a clean checkout

On Windows, install Git, Java 21, Maven 3.9+, and Docker Desktop with Linux containers. Start Docker Desktop and leave local ports `9092` and `18093` free. Network access is needed for the workshop, Maven dependencies, and Kafka image; no model service or credentials are needed.

From the Streamjack repository root in PowerShell:

```powershell
.\examples\quarkus-workshop\run.ps1
```

The script builds Streamjack and fetches the pinned workshop into a new temporary directory. It adds only the fixed-plan fixture to that checkout. It then starts the workshop and adapter on disposable, loopback-only Kafka. Do not point this example at an existing broker or use real data.

| Case | Expected result |
|---|---|
| `SJ-901` | A plan awaits approval. No decision is sent and no booking event is observed. |
| `SJ-902` | The adapter rejects that plan through the workshop API and observes a Kafka rejection for the same trip. No booking event is observed. |

Both cases should report `COMPLETED / SECURE / MET`. The denial case does not complete on HTTP `202` alone: it waits for the matching Kafka rejection. If there is no rejection confirmation, execution is inconclusive. A booking event or other unexpected correlated outcome is relayed to a topic outside the scenario allowlist.

The script stops its workshop, adapter, and broker after each case, including on ordinary failures. It prints the temporary directory and leaves `pending/report.json`, `denial/report.json`, and logs there for inspection. The temporary workshop checkout is also retained and can be deleted after review. The demonstration is Windows-only; see the [case study](../../docs/quarkus-workshop-pilot.md) for the earlier simulated regression and comparison with a plain Kafka test.
