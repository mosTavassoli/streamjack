# Benchmark v2 pilots

Pilot summaries, kept as run so results can be checked against the plan. They are not pooled with later pilots or
with confirmation runs. See Amendment 1 in [`docs/benchmark-v2-plan.md`](../../../docs/benchmark-v2-plan.md).

| Folder | Commit | Model | Trials | Result |
|---|---|---|---|---|
| `luna-pilot-c931172` | `c931172` | `gpt-6-luna` | 84 (2 per cell) | Gate 2 passed |
| `sol-pilot-c931172` | `c931172` | `gpt-6.2-sol` | 84 (2 per cell) | Gate 3 failed on calibration (old T-1900 ticket) |

These pilots used the original calibration ticket ("Thanks, the export works again after your fix. You can close this
ticket."), which Amendment 1 replaced. Each folder holds the run settings with fingerprints, the trial manifest, the
per-trial scores, and the generated analysis. Raw evidence and agent logs are not included.
