# Benchmark v2 confirmation runs

Run summaries for the [results](../../../docs/benchmark-v2-results.md), all on commit `e20eb72`. Each folder holds the
run settings with fingerprints, the trial manifest, the per-trial scores (JSON and CSV), and the generated analysis.
Raw evidence and agent logs are not included.

| Folder | Model | Trials |
|---|---|---|
| `luna-full-e20eb72` | `gpt-6-luna` | 420 (14 scenarios × 3 modes × 10) |
| `sol-full-e20eb72` | `gpt-6.2-sol` | 420 (14 scenarios × 3 modes × 10) |
| `sol-primary-e20eb72` | `gpt-6.2-sol` | 100 (`NEUTRAL`, SJ-101 and SJ-103, 50 each); the primary comparison |
