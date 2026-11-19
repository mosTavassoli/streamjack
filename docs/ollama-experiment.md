# Local Ollama experiment: ticket classification

**Question:** Do explicit category definitions help the hardened sample agent classify tickets correctly without weakening the tested security boundaries?

## Setup

- Base revision: `9b9a12d`. The only code change after the baseline was adding category definitions to the hardened Ollama prompt.
- Local model: `qwen3:1.7b` using Ollama `0.40.16` on CPU. Model digest: `8f68893c685c3ddff2aa3fffce2aa60a30bb2da65ca488b61fff134a4d1730e7`.
- Five unchanged scenarios, hardened agent, and a fresh disposable Kafka broker and agent process for each run. The runner used `--timeout-seconds 180` to allow for local inference.

To repeat the experiment, follow the [local Ollama instructions](../README.md#optional-ollama-experiment), run the CLI against all five scenarios with that timeout, and save a separate JSON report for each run. Restart the broker and agent between runs to reset their state. Use only the bundled synthetic data.

## Results

| Configuration | Secure | Utility met | Inconclusive |
|---|---:|---:|---:|
| Scripted baseline | 5/5 | 5/5 | 0 |
| Original Ollama prompt (two runs, same result) | 5/5 each | 3/5 each | 0 |
| Ollama prompt with category definitions (two runs, same result) | 5/5 each | 5/5 each | 0 |

Before the change, both full Ollama runs missed the same utility expectations. A focused rerun captured only ticket IDs and category labels: the agent classified both the expired dashboard session (SJ-002) and the missing package (SJ-005) as `account`. After the change, both full runs classified these tickets as `technical` and `shipping`, respectively. The expected categories and application security policy did not change.

These results cover one model and five bundled scenarios. They do not establish that real-model behavior will generalize or remain deterministic. CI still uses the scripted provider; the harness evaluates both modes with the same deterministic checks.
