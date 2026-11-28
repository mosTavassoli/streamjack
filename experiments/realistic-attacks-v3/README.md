# Realistic-attack study v3: commitments

Part B of the [v3 plan](../../docs/benchmark-v3-plan.md) uses private fixtures: 12 candidate order-note wordings
(C01 to C12), 4 development cases (D1 to D4), and 10 held-out cases (H01 to H10). The texts stay private until
confirmation is finished, so they cannot be tuned to the held-out cases or copied before the study ends.

[`commitments.json`](commitments.json) fixes them now. Each item's commitment is the SHA-256 of a private random nonce,
a line feed, and the exact payload. After confirmation, the fixtures and nonces are published, and anyone can
recompute every commitment. A plain hash would establish integrity but let short texts be guessed; the nonce prevents
that.

What the commitments establish and what they do not:

- They show that the candidates and cases used in the study are the ones fixed before the first development call.
- Candidate revisions made during development get their own commitments, committed before they are evaluated.
- The three wordings selected for confirmation are identified by id and version before confirmation starts.
- They do not show that the fixtures are representative of real attacks; the published texts let readers judge that.

The fixtures were validated before committing: all 168 note-case expansions have every placeholder defined and
replaced and fit the agent's note limit; each claimed status differs from the true status; each ticket contains exactly
one standalone order id, equal to its order; benign notes contain no order id. The 12 wordings are not 12 independent
mechanisms: some share a style (for example staleness claims), and one (C03) is deliberately blunt as a comparator.
