# Run the Quarkus workshop pilot

This example tests the approval boundary in the [Quarkus LangChain4j workshop trip planner](https://quarkus.io/quarkus-workshop-langchain4j/section-3/step-04), using the unchanged Streamjack runner and one small adapter. It uses synthetic trips, a fresh local Kafka broker for each case, and a fixed plan instead of a model. No real booking is made.

## Run from a clean checkout

Install Git, Java 21, Maven 3.9+, and a local Docker engine running Linux containers. Leave local ports `9092` and `18093` free. Network access is needed for the workshop, Maven dependencies, and Kafka image; no model service or credentials are needed.

**Linux:** The Bash launcher also needs `curl`, Python 3, `ss` (from `iproute2`), and `timeout` (from GNU Coreutils). From the repository root:

```sh
bash examples/quarkus-workshop/run.sh
```

**Windows:** Start Docker Desktop, then run from the repository root in PowerShell:

```powershell
.\examples\quarkus-workshop\run.ps1
```

The script builds Streamjack and fetches the pinned workshop into a new temporary directory. It adds only the fixed-plan fixture to that checkout. It then starts the workshop and adapter on disposable, loopback-only Kafka. Do not point this example at an existing broker or use real data.

| Case | Expected result |
|---|---|
| `SJ-901` | A plan awaits approval. No decision is sent and no booking event is observed. |
| `SJ-902` | The adapter rejects that plan through the workshop API and observes a Kafka rejection for the same trip. No booking event is observed. |
| `SJ-903` | The adapter approves that plan through the workshop API and observes one Kafka confirmation with a simulated booking reference for the same trip. |

All three cases should report `COMPLETED / SECURE / MET`. No manual approval is needed. The decision cases do not complete on HTTP `202` alone: they wait for the matching Kafka outcome. Without that outcome, execution is inconclusive. The adapter relays unexpected correlated outcomes, including a duplicate confirmation within the observation window, to a topic outside the scenario allowlist.

Both launchers stop their workshop, adapter, and broker after each case, including on ordinary failures. They print the temporary directory and leave `pending/report.json`, `denial/report.json`, `approval/report.json`, and logs there for inspection. The temporary workshop checkout is also retained and can be deleted after review. See the [case study](../../docs/quarkus-workshop-pilot.md) for the earlier simulated regression and comparison with a plain Kafka test.
