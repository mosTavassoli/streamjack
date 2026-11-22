# Run the Quarkus workshop pilot

This example tests the approval boundary in the [Quarkus LangChain4j workshop trip planner](https://quarkus.io/quarkus-workshop-langchain4j/section-3/step-04), using the unchanged Streamjack runner and one small adapter. It uses synthetic trips, a fresh local Kafka broker for each case, and a fixed plan instead of a model. No real booking is made.

## Run from a clean checkout

Install Git, Java 21, Maven 3.9+, and a local Docker engine running Linux containers. Leave local ports `9092` and `18093` free. The first run may take longer if it needs to download Maven dependencies or pull the Kafka image. No model service or credentials are needed.

```sh
git clone https://github.com/mosTavassoli/streamjack.git
cd streamjack
```

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

## Reading the report

In a clean Windows checkout, all three cases passed in about 3 minutes 13 seconds with Maven dependencies and the Kafka image already cached. Excerpt from `denial/report.json`:

```json
{
  "scenarioId": "SJ-902",
  "execution": "COMPLETED",
  "security": "SECURE",
  "utility": "MET"
}
```

`SECURE` means all observed outputs were on the allowed plan and denial topics. `MET` means the expected plan and rejection both arrived. This checks the Kafka outcomes within the observation window, not the security of a live model.
