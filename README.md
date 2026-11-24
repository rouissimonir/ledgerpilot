# LedgerPilot

I deploy AI agents into messy financial and enterprise systems, with evals and guardrails.

LedgerPilot is a public portfolio project for Mounir, a Java/Spring developer (~5 years) aiming at remote Forward Deployed Engineer and Applied AI roles. The ledger is a small double-entry system (Spring Boot, Postgres, Kafka). A Python agent reconciles it against source documents, explains the breaks, and stops for a person before any correcting entry is posted.

A deterministic scanner finds breaks. The model writes the explanation a reviewer reads. The API posts a correction only after a human approves that finding, and only for the scanner's delta.

Nothing here is implemented yet. The build schedule, module layout, and daily checklists are in [docs/PLAN.md](docs/PLAN.md).

## Status

| When | What is public |
| --- | --- |
| Week of Nov 24, 2026 | This plan. Ledger API slice targeted for Fri Nov 27. |
| Week of Dec 1 | Reconciliation agent, 10 seeded bugs, local evals. |
| Week of Dec 8 | Guardrail evals in GitHub Actions, Bedrock, a small AWS deploy, a one-page approval UI. |
| Week of Dec 15 | Demo video and this pilot plan filled in with measured results. |
| January 2027 | Text2SQL (read-only), 30 seeded bugs, stretch deploy of the ledger. |

## Pilot plan

Agreed on Nov 24, 2026, before the agent exists. Targets below stay fixed through December. If a number is missed, the README says so. It does not get edited after the fact to match the score.

### Baseline

**Metric:** minutes a person who did not write the scanner needs to review the week-2 dataset (10 seeded breaks + 3 clean events), and how many of the 10 breaks they name correctly (type and accounts).

**How:** one timed pass on Sat Dec 5, 2026, before any prompt tuning. Same dataset the agent scores against.

**Result:** not measured yet.

The scanner is the system's own baseline. On this dataset it should find every seeded break and raise nothing on the clean events. The model is not the baseline.

### Success targets

1. **Guardrail, 100%.** Across the eval set, zero correcting transactions are posted unless a human approved that finding. The posted amount is the scanner's delta. A model-supplied amount is never written. One failure fails the week.
2. **Scanner recall, 100% on the 10 bugs, 0 findings on the 3 clean events.** A miss here is a bug in the scanner, not a model miss.
3. **Explanation faithfulness, at least 8 of 10.** On one live model run, the rationale mentions the correct finding type, account code, and amount, and it mentions no amount that is not in the finding.
4. **Idempotency.** Replaying a successful `Idempotency-Key` returns the original transaction and does not create a second one. Covered by the Testcontainers test.
5. **Reviewers, 2 of 3.** Two of the three first users can approve one finding and reject one from the written script, without Mounir using the keyboard.

### First slice

**By Fri Nov 27, 2026:** a local ledger API. A balanced transaction posts, an unbalanced one is rejected, and repeating the same idempotency key returns the original result. Chart of accounts seeded. One Testcontainers test. No agent in this slice.

**Public artifact that week:** this README plus `docker compose` instructions once the slice runs.

### First users

The first users are three people and a script:

| Who | When | What they do |
| --- | --- | --- |
| Reviewer A, finance-literate, not the author | Week of Dec 8 | 20 minutes on the local demo. Says whether a finding is intelligible. |
| Reviewer B, backend peer | Week of Dec 8 | Same script, focused on the approval gate and idempotent replay. |
| Mounir, cold | Wed Dec 16 | Runs the demo from the README only, a day after last touching the code. |

Notes from those sessions land in the README in week 4, including feedback that was not acted on.

### Decision gate (Sun Dec 14)

If the guardrail tests are red, or scanner recall is below 100% on the 10 bugs, the AWS and UI work stops until those are green. A deployed endpoint does not count as success against the targets above.

### Non-goals through December

Authentication, multi-currency FX, real bank feeds, the model posting on its own, Text2SQL, a full Next.js app, moving the ledger off Postgres.
