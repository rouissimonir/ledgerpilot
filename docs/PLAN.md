# LedgerPilot build plan

Mounir implements this. This document is the plan only.

**Pitch:** I deploy AI agents into messy financial and enterprise systems, with evals and guardrails.

**Time budget:** ~3h weekday mornings, Sat 4h, Sun 2h. One public push every week. One outcome per sitting. Stop when that day's done line is true. If a Friday slips, the weekend finishes the definition of done and the next week drops the first item in [If time runs out](#if-time-runs-out).

**Locked product rules:**

- The scanner decides what is wrong. The model writes the explanation. The model never supplies an amount that gets posted.
- A correction is a new journal entry, except two cases the scanner names: a balance-row recompute (`BALANCE_DRIFT`) and a single appended posting on an unbalanced journal (`UNBALANCED_JOURNAL`). Everything else leaves history in place.
- The API posts a correction only when that finding is `APPROVED`, the caller is the apply step, and the idempotency key is `corr-{findingId}`.
- Logs and model prompts carry ids, account codes, sides, and minor-unit amounts. They do not carry descriptions, memos, or raw request bodies.
- Pilot targets in the README are fixed as of Nov 24, 2026.

## Module layout

Create directories only in the week that uses them.

```text
ledgerpilot/
├── README.md
├── docs/
│   └── PLAN.md
├── docker-compose.yml                 # week 1: Postgres, Kafka, ledger-api
├── ledger-api/                        # week 1, Maven, Java 21, Spring Boot 3
│   └── src/main/java/dev/mounir/ledgerpilot/
│       ├── LedgerApplication.java
│       ├── account/                   # entity, repository, chart seed
│       ├── ledger/                    # transaction, posting, balance, validation
│       ├── idempotency/
│       ├── outbox/                    # week 1 weekend
│       ├── finding/                   # week 2: findings, decisions, corrections
│       └── api/                       # controllers, error body
├── agent/                             # week 2, Python 3.12, uv
│   ├── src/ledgerpilot_agent/
│   │   ├── graph.py                   # LangGraph
│   │   ├── scan.py                    # deterministic checks
│   │   ├── explain.py                 # model call, template fallback
│   │   ├── tools.py                   # HTTP reads against the ledger API
│   │   ├── model.py                   # stub | bedrock, same interface
│   │   └── redact.py
│   └── evals/
│       ├── dataset/v1/cases.yaml
│       ├── fixtures/hypotheses.json   # frozen prose inputs for CI
│       └── score.py
├── ui/
│   └── index.html                     # week 3: one page, no framework
└── infra/aws/template.yaml            # week 3: SAM
```

Week 1 package root is `dev.mounir.ledgerpilot`. One Maven module. No parent POM, no shared libraries, no API gateway in front of Spring.

Kafka runs in Compose from day one so the URL never changes, using KRaft (no ZooKeeper). The app does not publish until the weekend outbox task.

## Data model

Money is a `bigint` of minor units (`amountMinor`). The API rejects decimals. One currency per transaction in the API (`USD` only in the seed). The database still stores a currency code on each posting so a SQL seed can create a mismatch.

### Week 1 tables

**account**

| Column | Notes |
| --- | --- |
| id | UUID PK |
| code | unique, text. Seed codes below. |
| name | text |
| type | `ASSET`, `LIABILITY`, `EQUITY`, `INCOME`, `EXPENSE` |
| normal_side | `DEBIT` or `CREDIT` |

Normal side: asset and expense debit; liability, equity, and income credit.

Seed chart, loaded by Flyway:

| Code | Name | Type |
| --- | --- | --- |
| 1000 | Cash | ASSET |
| 1100 | Accounts receivable | ASSET |
| 2000 | Accounts payable | LIABILITY |
| 3000 | Owner equity | EQUITY |
| 4000 | Sales revenue | INCOME |
| 5000 | Operating expense | EXPENSE |
| 9999 | Suspense | ASSET |

**transaction**

| Column | Notes |
| --- | --- |
| id | UUID PK |
| idempotency_key | unique, the successful request's key |
| external_ref | nullable, indexed, **not** unique. Duplicates are a seeded bug. |
| description | text, stored, never logged, never sent to the model |
| occurred_at | timestamptz |
| created_at | timestamptz |
| corrects_finding_id | nullable UUID. Set only on agent corrections. No FK until week 2. |

**posting**

| Column | Notes |
| --- | --- |
| id | UUID PK |
| transaction_id | FK |
| account_id | FK |
| side | `DEBIT` or `CREDIT` |
| amount_minor | bigint, must be > 0. Direction lives in `side`. |
| currency | char(3) |

**account_balance**

| Column | Notes |
| --- | --- |
| account_id | PK, FK |
| debit_minor | bigint, running sum |
| credit_minor | bigint, running sum |

Updated in the same database transaction as the postings. The scanner's drift check is `stored debit/credit` versus `sum(postings)`. Do not treat the balance row as the source of truth.

**idempotency_record** — see below.

**outbox** (weekend of week 1)

| Column | Notes |
| --- | --- |
| id | UUID PK, also the event id |
| topic | text |
| aggregate_id | text |
| payload | jsonb |
| created_at | timestamptz |
| published_at | nullable timestamptz |

### Week 2 tables

**source_document** — the other side of the reconciliation. One row per economic event the business believes happened.

| Column | Notes |
| --- | --- |
| id | UUID PK |
| external_ref | unique |
| expected_amount_minor | bigint |
| currency | char(3) |
| debit_account_code | text |
| credit_account_code | text |
| issued_on | date |

**finding**

| Column | Notes |
| --- | --- |
| id | UUID PK |
| run_id | UUID |
| type | closed enum, list below |
| status | `PROPOSED`, `APPROVED`, `REJECTED`, `CORRECTED`, `ACKNOWLEDGED` |
| account_codes | text array |
| amount_minor | bigint, scanner delta. Always positive. |
| currency | char(3) |
| evidence | jsonb of transaction ids and source-document ids only |
| rationale | text produced by the model or the template |
| decision_actor | nullable text |
| decided_at | nullable timestamptz |
| correction_transaction_id | nullable FK |

Closed finding types, one per seeded bug:

`BALANCE_DRIFT`, `UNBALANCED_JOURNAL`, `DUPLICATE_EVENT`, `AMOUNT_MISMATCH`, `WRONG_ACCOUNT`, `SWAPPED_SIDES`, `CURRENCY_MISMATCH`, `ROUNDING_GAP`, `SUSPENSE_BALANCE`, `MISSING_ENTRY`.

### Invariants the API enforces

A request is rejected with 422, and the idempotency key is **not** consumed, when:

- fewer than 2 or more than 8 postings
- `amountMinor` < 1
- unknown account code
- more than one currency on the transaction
- sum of debit minor units ≠ sum of credit minor units

The seed script inserts the illegal rows with SQL. That is the point of those bugs.

### Idempotency

`Idempotency-Key` header is required (1–255 chars). The body hash is SHA-256 of canonical JSON: keys sorted, postings ordered by `accountCode` then `side`, covering exactly `occurredAt`, `externalRef`, `description`, and `postings`. Description is inside the hash, so a wording change under the same key returns 409.

On **success only**, the ledger inserts and the `idempotency_record` (`key` PK, `request_hash`, `transaction_id`, `response_code`, `response_body`, `created_at`) commit in one transaction. A validation failure stores nothing, so the client can fix the body and reuse the key.

Replay: same key and same hash returns the stored status and body and writes nothing. Same key and different hash returns 409. Concurrent duplicates: the unique key lets one commit; the loser rolls back. Catch the constraint failure **outside** the `@Transactional` method (tainted persistence context if you catch inside it), then read the stored row in a new read-only transaction and apply the hash check.

Correction posts use key `corr-{findingId}`, generated by the server, not the client.

### Ledger endpoint set

| Method | Path | Week |
| --- | --- | --- |
| POST | `/transactions` | 1 |
| GET | `/accounts/{code}/balance` | 1 |
| GET | `/transactions/{id}` | 1, only if Monday Nov 30 is free |
| GET | `/reconcile/snapshot` | 2. Balances, postings, source documents. No descriptions or memos. |
| POST | `/findings/{id}/decisions` | 2. Body: `decision` = `APPROVED` or `REJECTED`, `actor` text. |
| POST | `/findings/{id}/correction` | 2. Called by the apply step. Refuses unless status is `APPROVED`. Builds the journal from the finding type and `amount_minor`. |

`POST /transactions` request:

```json
{
  "externalRef": "INV-1042",
  "description": "Invoice 1042",
  "occurredAt": "2026-11-24T08:00:00Z",
  "postings": [
    { "accountCode": "1100", "side": "DEBIT", "amountMinor": 150000, "currency": "USD" },
    { "accountCode": "4000", "side": "CREDIT", "amountMinor": 150000, "currency": "USD" }
  ]
}
```

First response is 201. Replay is the stored 201 and the same body.

## Kafka

Three topics. Publish from the outbox poller (`@Scheduled` 1s). At-least-once. Consumers dedupe on `eventId`. No Kafka transactions in this project.

| Topic | Key | When |
| --- | --- | --- |
| `ledger.transactions.posted` | transaction id | after a successful post, including corrections |
| `ledger.reconciliation.proposed` | finding id | finding stored as `PROPOSED` |
| `ledger.reconciliation.decided` | finding id | human decision committed |

Envelope, same for all three:

```json
{
  "eventId": "uuid",
  "type": "ledger.transaction.posted",
  "occurredAt": "2026-11-28T15:00:00Z",
  "aggregateId": "uuid",
  "payload": {}
}
```

`payload` for a posted transaction: `transactionId`, `externalRef`, posting `{accountCode, side, amountMinor, currency}`. No description.

`payload` for a finding: `findingId`, `runId`, `findingType`, `accountCodes`, `amountMinor`, `currency`, `evidenceIds`. No rationale text (it is free text and stays in Postgres).

`payload` for a decision: `findingId`, `decision`, `actor`, `correctionTransactionId` or null.

Week 2 can ship the agent without a consumer. The outbox row is the integration test. A consumer is not on the critical path.

## Agent

LangGraph, one process, run from a CLI: `python -m ledgerpilot_agent run`. The graph checkpoints locally with the SQLite saver so a pause survives a restart. Postgres checkpointing is out of scope.

```text
load_snapshot → scan → explain → propose → (interrupt) → apply → summarize
```

**load_snapshot.** `GET /reconcile/snapshot`. State holds that JSON and a new `run_id`. No model.

**scan.** Pure functions, one per finding type. They append findings. They do not call the model.

| Type | Check |
| --- | --- |
| BALANCE_DRIFT | stored debit/credit ≠ sum of postings for that account |
| UNBALANCED_JOURNAL | one transaction's debits ≠ credits |
| DUPLICATE_EVENT | more than one transaction for a source `external_ref` |
| AMOUNT_MISMATCH | linked amount differs from the source by more than 1 minor unit |
| WRONG_ACCOUNT | linked accounts differ from the source pair, unless the pair is exactly reversed (`SWAPPED_SIDES`) |
| SWAPPED_SIDES | debit and credit codes are the source pair reversed |
| CURRENCY_MISMATCH | a transaction's postings use more than one currency |
| ROUNDING_GAP | source amount − linked total = 1 minor unit |
| SUSPENSE_BALANCE | account 9999 has a non-zero net |
| MISSING_ENTRY | source document with zero transactions |

**explain.** One model call per finding. Input: type, account codes, amount, evidence ids. Output: a short rationale. The prompt says the model cannot add findings or change the amount. If the call errors, the guardrail blocks, or the output mentions an amount that is not the finding's amount, store a fixed template instead (`{type} on {accounts} for {amountMinor} {currency}`). A model outage must not hide a real break.

**propose.** Insert `PROPOSED` rows and outbox events. Then interrupt. Nothing is posted.

**apply.** Resume by reading decisions from Postgres. The resume payload is not the authority. For each `APPROVED` finding, call `POST /findings/{id}/correction`. The server picks the repair from the table below. `amount_minor` on the finding is the size of that repair. Evidence may also carry `expectedAmountMinor` and `actualAmountMinor` so the reviewer can see both figures. The model does not send either number.

| Type | Approved repair |
| --- | --- |
| `BALANCE_DRIFT` | Recompute `account_balance` from postings. No journal. A new journal would change the books to match a bad cache. |
| `UNBALANCED_JOURNAL` | Append the missing posting on the original journal (side and amount from the scanner). A later balanced journal cannot make an unbalanced history sum to zero. This is the one edit of a posted journal, and it is a separate repository method used only here. |
| `DUPLICATE_EVENT` | New journal reversing the extra transaction. |
| `AMOUNT_MISMATCH` | New journal for the absolute difference, in the direction of the source document. |
| `WRONG_ACCOUNT` | New reclass journal for the event amount, from the account that was used to the account on the source document. |
| `SWAPPED_SIDES` | New journal for twice the event amount, in the source direction. Swapping both sides leaves a 2× gap. |
| `CURRENCY_MISMATCH` | No journal. Status becomes `ACKNOWLEDGED`. Inventing an FX rate is out of scope. |
| `ROUNDING_GAP` | New journal for the 1 minor unit gap. |
| `SUSPENSE_BALANCE` | New journal that clears 9999 against 3000 (owner equity). The offset account is fixed in the scanner. |
| `MISSING_ENTRY` | New journal equal to the source document. |

Reject writes nothing. A second apply no-ops because status is already `CORRECTED` or `ACKNOWLEDGED`. Journal repairs also store `corr-{findingId}`, so a retried post returns the original journal.

**summarize.** Counts only: proposed, approved, rejected, corrected, template-fallback. Printed locally. Not a model call.

### Server-side gate

`POST /findings/{id}/correction`:

1. Finding exists and status is `APPROVED`. Otherwise 409.
2. Idempotency key is exactly `corr-{findingId}`.
3. The repair is built in Java from the finding row and the table above. The request body has no amounts.
4. Any new journal goes through the same balanced-entry path as a normal post. The unbalanced-journal append is the only path that adds a line to an existing journal, and it refuses every type except `UNBALANCED_JOURNAL`.
5. Status becomes `CORRECTED` when a journal or a balance recompute lands, and `ACKNOWLEDGED` for `CURRENCY_MISMATCH`. Same database transaction as the repair.

### Seeded bugs (10) and clean controls (3)

Reset script: drop volumes, `docker compose up`, Flyway, then `psql` the seed files. Eval runs use that reset, not a dirty database.

Each bug is one case id, one finding type, one evidence set. Amounts are minor units.

| Case | What the seed does |
| --- | --- |
| b01 | SQL: `account_balance` for 1100 overstated by 500 |
| b02 | SQL: one journal whose credits are short 100 |
| b03 | Two API posts, different idempotency keys, same `external_ref` |
| b04 | Source says 10000, ledger has 1000000 (scale error) |
| b05 | Source says debit 1000 / credit 4000, ledger used 5000 / 4000 |
| b06 | Source pair reversed across the two sides |
| b07 | SQL: USD debit, EUR credit, equal numbers |
| b08 | Source 10000. One balanced journal for 9999 (debit 1100, credit 4000). Gap of 1. |
| b09 | SQL: suspense net 2500 with no source document |
| b10 | Source document and no transaction |
| c01–c03 | Three source documents, each with one matching balanced post |

Case files live in `agent/evals/dataset/v1/cases.yaml`: `id`, `seed`, `expected_type`, `expected_accounts`, `expected_amount_minor`.

## Evals

Two layers. They answer different questions.

**Scanner tests** (unit, no model). Given a fixture snapshot, `scan.py` returns exactly the expected findings and nothing else. This is the recall target of 100%.

**Explanation tests.** Feed frozen finding JSON plus a frozen model string from `fixtures/`. Score faithfulness:

- the model text contains the finding type, each expected account code, and the repair amount
- after removing those account codes and that amount, no other 3-digit-or-longer number remains

Score the model text before the template fallback. A fallback still stores a correct rationale for the reviewer, and the case counts as a faithfulness miss.

**Guardrail tests** (the CI gate):

- `PROPOSED` or `REJECTED` changes no balance and inserts no transaction
- `APPROVED` + `MISSING_ENTRY` inserts one balanced transaction, key `corr-{id}`, amount equal to the source
- `APPROVED` + `BALANCE_DRIFT` fixes the balance row and inserts no transaction
- `APPROVED` + `CURRENCY_MISMATCH` sets `ACKNOWLEDGED` and inserts no transaction
- a second apply makes no further change
- a model rationale that cites a wrong amount is stored as the template and scored as a miss
- the redact helper drops `description`, `memo`, `rationale`, and `prompt` keys

**Live run** (not a merge gate). One command runs all 13 cases against Bedrock (or the week-2 local model), writes `agent/evals/last-report.json`, and optionally logs a LangSmith experiment if `LANGSMITH_API_KEY` is set. Metrics in the report: scanner recall, clean-case false findings, explanation faithfulness, guardrail pass. LangSmith dataset name: `ledgerpilot-v1`. Feedback keys: `type_match`, `evidence_match`, `guardrail_ok`.

Do not use an LLM-as-judge.

### CI wiring (week 3)

`eval-guardrails.yml` on pull request, no cloud credentials:

- JDK 21, `mvn -B test` (Testcontainers on the GitHub runner)
- Python 3.12, `pytest` for scanner, explanation fixtures, and guardrail tests

`eval-live.yml` on `workflow_dispatch` only:

- AWS credentials from repo secrets, Bedrock model id from a variable
- not required for merge
- uploads `last-report.json` as an artifact

Week-2 local stand-in, before Actions exist: `pytest` and `mvn test` on the laptop. That is enough to fill the Dec 5 baseline.

## AWS and Bedrock

Week 3 deploys the **agent API**, not the ledger. The ledger stays on Compose. The recorded demo uses the local path, where an approval posts a real correcting journal. The cloud path explains findings and records approvals. It does not write to Postgres. Say that in the README so the two paths are not blurred.

SAM stack `infra/aws/template.yaml`, personal account, region fixed in `samconfig` (one region):

| Resource | Role |
| --- | --- |
| Lambda (Python, zip) | `POST /runs`, `POST /findings/{id}/decisions`, `GET /findings`. Snapshot of the 13 cases is in the zip. |
| HTTP API | Those three routes. No auth in the demo. The README says so. |
| DynamoDB `findings` | PK `findingId`. Status, amounts, accounts, rationale, decision. |
| DynamoDB `runs` | PK `runId`. |
| Bedrock guardrail | PII filters on input and output (name, email, phone, address, SSN-like). One denied topic: posting or correcting without human approval. |
| Budget alarm | $10 monthly, email. |

Model access is the Bedrock Converse API. `BEDROCK_MODEL_ID` is an env var, pinned to one id (a current Claude Sonnet or Amazon Nova chat model that the account has enabled). No model id is hardcoded in more than `model.py` and the SAM parameter.

IAM on the function role, resource ARNs only:

- `bedrock:InvokeModel` and `bedrock:Converse` on that foundation-model ARN (and the inference-profile ARN if the id requires it)
- `bedrock:ApplyGuardrail` on the guardrail ARN
- `dynamodb:GetItem`, `PutItem`, `UpdateItem`, `Query` on the two tables
- `logs:CreateLogStream` and `logs:PutLogEvents` on this function's log group

No `*` resource, no VPC, no NAT, no RDS in December. After the demo video, `sam delete`.

Logging: JSON logger with an allowlist (`runId`, `findingId`, `findingType`, `accountCodes`, `amountMinor`, `currency`, `decision`, `modelId`, `guardrailAction`). Redact helper unit-tested. LangSmith tracing of full prompts stays off in the Lambda.

Local Bedrock (Wed of week 3) uses the same `model.py` with a developer profile. No access keys in the repo or the Compose file.

January ledger deploy, only if the December stack still deploys: ECS Fargate service for `ledger-api`, RDS Postgres, no Kafka (outbox publisher disabled or pointed at a log). The Lambda then calls that private API. Skip MSK. If the Monday check in January fails, do the audit-log week instead (see January).

## Week 1 — Nov 24–30, 2026

**Definition of done (Fri Nov 27):** `docker compose up` runs Postgres, Kafka, and the API. Flyway seeds the chart. A balanced post returns 201 and moves both balance rows. An unbalanced post returns 422 and writes nothing. Same idempotency key and body returns the stored response and one transaction row. Same key, different body returns 409. One Testcontainers test covers the balanced post, the balance sums, and the replay. README has run steps and the pilot plan. Descriptions are not logged.

Thu Nov 26 is Thanksgiving in the US. The 3h can move to Saturday morning if needed. Friday's done line does not move.

### Tue Nov 24 (3h) — skeleton boots

- Maven module, Java 21, Spring Boot 3, Flyway, Actuator.
- Compose: Postgres 16, Kafka KRaft, API waits for Postgres.
- Migrations for account, transaction, posting, account_balance, idempotency_record. Empty outbox table is fine today.
- Done when the app is healthy and `\dt` shows those tables.

### Wed Nov 25 (3h) — post a balanced entry

- Entities and the ledger service. Validation rules in the invariants section.
- Chart seed.
- Service test with Testcontainers **or** a thrown-away main method. Prefer starting the Testcontainers test today if it is going well.
- Done when one balanced entry is committed and both `account_balance` rows match the postings.

### Thu Nov 26 (3h) — HTTP and idempotency

- `POST /transactions` and `GET /accounts/{code}/balance`.
- Idempotency facade as specified (catch outside the transaction).
- Done when curl against Compose shows 201, replay, 409 on a reused key, 422 on an unbalanced body.

### Fri Nov 27 (3h) — the test is the release

- One `LedgerPostingIT`: Testcontainers Postgres, post, assert postings and balances, replay, assert a single transaction.
- README: how to run, the curl, pilot plan left as in the skeleton with baseline still "not measured".
- Done when `mvn test` is green and the README steps work from a clean clone of the instructions.

### Sat Nov 28 (4h) — outbox, or finish Friday

- If Friday is red, Friday's list only.
- If Friday is green: write the outbox row in the posting transaction, poller publishes `ledger.transactions.posted`, a test asserts the outbox row (no Kafka container).
- Add `.github/workflows/ledger-api.yml` running `mvn -B test` if the outbox work finished with time left.
- Done when a posted transaction has one unpublished-then-published outbox row, or when Friday's gap is closed. Do not start both if Friday slipped.

### Sun Nov 29 (2h) — stranger test

- Read the README as someone who has not seen the code. Fix the steps.
- Confirm no secrets, no request-body logging.
- Done when the curl block matches the running API.

### Mon Nov 30 (3h) — buffer

- Behind: finish the definition of done. Ahead: `GET /transactions/{id}` only.
- Done when week 1's definition of done is true. Do not create the Python package.

## Week 2 — Dec 1–7

**Definition of done:** reset script loads 10 bugs and 3 clean events. `scan.py` scores 10/10 and 0 false findings on the clean events. The graph pauses before any correction. Approve posts one `corr-{id}` transaction. Reject posts nothing. Replay posts nothing new. `pytest` covers the scanner and the gate. Human baseline minutes and misses are written into the README on Dec 5, before any prompt edit. README has the demo commands.

About 21h. The model is not on the done line.

### Tue Dec 1 (3h) — snapshot the ledger

- `GET /reconcile/snapshot` (codes, sides, amounts, ids, source documents; no description).
- Python package, `httpx` client, print one balance.
- Done when a hand-edited balance row shows up as a difference in the snapshot.

### Wed Dec 2 (3h) — seeds

- Flyway for `source_document` and `finding`.
- Seed files for b01–b10 and c01–c03. `cases.yaml` filled in.
- Reset script.
- Done when reset, then a SQL count, matches the case file (10 bad, 3 good).

### Thu Dec 3 (3h) — scanner

- Implement the 10 checks. Unit tests on a JSON snapshot, no running database required for those tests.
- Done when scanner tests are 10/10 and clean is empty. Stop. Do not start the model today.

### Fri Dec 4 (3h) — approval gate

- Decision endpoint and correction endpoint, server builds the postings.
- LangGraph through `propose`, then interrupt. Apply node calls the correction endpoint.
- A stub `explain` returns the template. No live model required.
- Done when approve, reject, and double-apply behave as in the definition of done, exercised by a test.

### Sat Dec 5 (4h) — measure, then one live call

- Run the human baseline first. Write minutes and named misses into the README. Do not tune anything before that number is written.
- Wire `explain` to whatever model key is already on the laptop, for one case. Fallback template stays.
- Done when the README has the baseline number and one live rationale is either shown or explicitly skipped because no key was available.

### Sun Dec 6 (2h) — faithfulness scorer

- Scorer on one frozen good rationale and one frozen bad rationale.
- Done when the bad fixture fails the test and the good one passes.

### Mon Dec 7 (3h) — public demo

- README: reset, run, approve, reject, what the correction looks like.
- Push. LangSmith only if a key is already handy and the demo text is done.
- Done when the demo commands match a clean run. One public commit.

## Week 3 — Dec 8–14

**Definition of done:** `eval-guardrails` is green on a pull request with no model secrets. `model.py` can call Bedrock for one case from the laptop. `sam deploy` creates the Lambda, HTTP API, and two tables with the IAM above. A guardrail is attached; a log line from a run contains only allowlisted fields. A browser page can approve and reject against the **local** API, and the ledger shows one correction. `sam delete` is in the README. The live workflow file exists and is dispatch-only.

About 21h. Order of sacrifice if a day slips: live workflow, then UI polish, then guardrail-console screenshots. Do not sacrifice the guardrail tests or the local approve path.

### Tue Dec 8 (3h) — Actions

- `eval-guardrails.yml` as specified.
- Done when a pull request is green without AWS or LangSmith secrets.

### Wed Dec 9 (3h) — Bedrock on the laptop

- `model.py` Converse call, env model id, developer profile.
- Guardrail id optional today.
- Done when one finding gets a Bedrock rationale or falls back to the template, and scanner output is unchanged.

### Thu Dec 10 (3h) — SAM

- Template, scoped IAM, budget alarm, deploy.
- Done when curl hits `GET /findings` on the deployed API (empty list is fine).

### Fri Dec 11 (3h) — cloud run plus guardrail

- Lambda loads the bundled snapshot, scans, explains via Bedrock, writes Dynamo.
- Guardrail attached. Decision route updates Dynamo and does not call the ledger.
- Done when one cloud run is stored and a sampled log line has no description, memo, or prompt.

### Sat Dec 12 (4h) — one page

- `ui/index.html` talking to the local API. Table of findings, Approve, Reject.
- Banner: demo, no auth.
- Done when a click approves a finding and a second click on an already decided row does not post another transaction.

### Sun Dec 13 (2h) — dispatch workflow and teardown

- `eval-live.yml`, `workflow_dispatch` only.
- README: deploy, cost, `sam delete`, and the sentence that cloud approvals do not post to Postgres.
- Done when both are written. Running the live job is optional today.

### Mon Dec 14 (3h) — gate

- Re-read the [decision gate](../README.md#decision-gate-sun-dec-14). Fix guardrails or scanner before any polish.
- If green, send the script to reviewer A or B.
- Done when the week-3 definition of done is true, or the README lists exactly one item that slipped and why.

## Week 4 — Dec 15–20

**Definition of done:** README pilot plan has the Dec 5 baseline, the locked targets, and the actual scores next to them. Demo video is linked (about 5 minutes) plus `docs/demo-script.md` so it works without audio. A clean run of Compose, `mvn test`, and `pytest` was done once on Fri Dec 18. No new feature commits. Sat Dec 19 and Sun Dec 20 are exam days.

### Tue Dec 15 (3h) — README truth pass

- Scores, known gaps, mermaid of scan → explain → interrupt → apply.
- Done when a reader can see every target and whether it was hit.

### Wed Dec 16 (3h) — cold demo

- Mounir runs only from the README. Fix demo-blocking bugs. No new endpoints.
- Done when the path (post, replay, seeded bug, reject, approve, one correction, eval command) completes.

### Thu Dec 17 (3h) — record

- 4–6 minute screen recording with the script in `docs/demo-script.md`.
- Link it from the README.
- Done when the link plays and the script matches the video.

### Fri Dec 18 (3h) — freeze

- Clean checkout of the three commands.
- Short "what a real deployment would add" section: auth, RDS, ECS for the ledger, audit log. No code.
- Done when those commands pass and the branch is pushed. Stop.

### Sat Dec 19 and Sun Dec 20

AWS exam. No project work. If a README link is broken and it takes under 15 minutes Sunday, fix the link. Otherwise leave it.

## January 2027

Start Mon Jan 4. Same weekly budget (~21h). Each Monday, cut the week into 3h outcomes. Do not pull a later week forward.

Christmas week (Dec 21–Jan 3) is off.

### Jan 4–10 — read-only Text2SQL

**Done when:** a tool answers 10 numeric questions from a view, CI rejects unsafe SQL, and the model cannot post a transaction from a query result.

- Mon: view `ledger_public` (codes, sides, amounts, ids, dates). No description, no memo.
- Tue: parser allows one `SELECT`, rejects a second statement, comments used as a bypass, `INSERT`/`UPDATE`/`DELETE`, and any column not on the view. Row cap 200.
- Wed: 10 questions with expected numbers in the eval set.
- Thu: graph node runs parser → query → answer. CI uses frozen SQL. One live Bedrock try.
- Fri: wire the new tests into `eval-guardrails`.
- Sat: at most two prompt edits. Record the score. Stop.
- Sun: README section, including the misses.

### Jan 11–17 — 30 bugs

**Done when:** 30 cases (the original 10 plus 20 variants, including a needle inside ~50 clean events) reset cleanly, scanner recall and false findings are measured, and any new target is written down **before** a prompt edit.

- Mon: list the 20 new case ids. Variants of the existing types, not new types, unless a check is already trivially shared.
- Tue–Wed: seed SQL.
- Thu: the clean background set.
- Fri: scanner only. Fix misses in code.
- Sat: measure. Write the number. One explanation-prompt edit only if faithfulness fell.
- Sun: short note in the README.

### Jan 18–24 — ledger on AWS, or an audit log

Monday kill-criteria: week-3 stack still deploys, and `mvn test` is green. If either is false, do the audit week. Do not debug ECS and a broken SAM stack in the same week.

**Deploy week, if the check passes.** ECS Fargate for the API, RDS Postgres, public demo still callable, Lambda tools pointed at that API, no Kafka. Done when a local client posts an idempotent transaction to the deployed API and the Testcontainers suite is still the correctness gate.

**Audit week, otherwise.** Append-only `audit_event` written in the same transaction as posts and decisions. A one-page threat note: no auth, demo data only, what would have to be true before anyone else's books touched this. Done when the audit row exists for a post and a decision, and the note is in `docs/`.

### Jan 25–31 — talk track

**Done when:** `docs/fde-notes.md` has three short stories (the idempotency race and the Spring transaction boundary, why the scanner is not the model, a guardrail or logging miss and the test that locks it), one reviewer has run the January build or the skip is recorded, and a tag `demo-jan` points at the freeze.

No new scope after Thursday of this week.

## If time runs out

When a week slips, drop the left column first. The right column stays.

| Drop first | Never drop |
| --- | --- |
| Next.js, beyond the one static page | Testcontainers idempotency test |
| LangSmith on pull requests | Scanner recall at 100% of the cases that exist that week |
| Cloud path writing back to Postgres | Server-side approval gate |
| Kafka consumer | Allowlist logging |
| Extra finding types | README targets left intact, with honest scores |
| ECS / RDS if the Monday check fails | Exam days Dec 19–20 |
