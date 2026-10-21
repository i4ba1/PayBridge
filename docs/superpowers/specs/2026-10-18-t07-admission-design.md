# T07 design: payment admission and `requestPayment`

Date: 2026-10-18. Task: Implementation Plan T07. Depends on T04 (schema, stores) and T05 (identity, authorization).
Sources: Plan T07, TIS section 9, DMR 6.1 and the lock rules in DMR section 2, API contract sections 5 and 7.

## 1. Purpose and success criteria

`requestPayment` is the first vertical slice that must never produce a duplicate collection. It accepts a payment for one
invoice, durably and idempotently, without ever calling the provider.

Success means:

- An identical repeat returns the same payment id and its current state. A changed payload returns `IDEMPOTENCY_CONFLICT` (FR-011).
- If any required insert fails, no payment is acknowledged and no outbox row exists (FR-012).
- Two different keys on one invoice never produce two active collections: the application lock prevents it, and the
  `ux_payment_one_active` index prevents it even when the lock is bypassed in a test (FR-009).
- A rejection creates no payment, and a replay of an accepted key is served even when a gate is closed (FR-015).
- Amount and currency come only from the invoice (FR-007). A payment of another merchant is never confirmed to exist (FR-004).
- 50 threads over 5 keys on one invoice produce exactly one payment row; the rest are replays or `INVOICE_NOT_ELIGIBLE`.

Out of scope: provider calls and the executor (T08, T09), the relay that publishes the outbox (T09), real quota and capacity
(T15, T18), the scenario administration API (T16, T17), the 64 KiB body and 8 s execution limits (T21).

## 2. Decisions

| # | Decision | Reason |
|---|---|---|
| D1 | The write path uses the exact DMR 6.1 SQL through `JdbcTemplate` inside one `TransactionTemplate`. JPA stays for reads. | The lock order and the error codes (1062, 1205, 1213) must be visible and testable statement by statement. |
| D2 | V003 and V004 ship in full now, with their grants. | Admission needs `payment_event`, `outbox` and `system_flag`. Shipping the migrations as the DMR specifies keeps names stable; T09 and T16 add behavior only. |
| D3 | `AdmitPayment` is pure Java in `application`; the transaction lives behind an `AdmissionStore` port. | Orchestration is testable with fakes; the adapter owns SQL and nothing else. |
| D4 | The scenario run stamped on a payment is the configured `paybridge.admission.default-scenario-run-id` (default `default`), resolved through a `ScenarioRunResolver` port. | Merchant scenario runs have no storage until the scenario admin API (T16/T17). The port lets that task plug in the real lookup. |
| D5 | Quota and capacity are an `AdmissionGate` port with permissive implementations. | Redis quota is T15 and the capacity monitor is T18. |
| D6 | Retry a whole admission transaction at most 3 times with jitter on deadlock (1213) or lock wait timeout (1205), then answer `TEMPORARILY_UNAVAILABLE`. | Plan risk note; the client keeps its key. |

## 3. Schema

Two migrations, written exactly as the DMR lists them, with `UUID_T` expanded:

- `V003__durable_work.sql`: `payment_event`, `outbox`, `processing_task`, `webhook_inbox`, `reconciliation_case`.
- `V004__support_quota_and_flags.sql`: `reconciliation_note`, `merchant_quota_setting`, `dead_letter_record`, `system_flag`
  (with the two seeded rows `admission.capacity = OPEN` and `admission.maintenance = OFF`).

`db/grants/table-grants.sql` gains the DMR grants for every new table (including `DELETE` on `outbox`, `SELECT, INSERT` only on
`payment_event`, `reconciliation_note`). `TableGrantsIT` keeps failing if a table lacks a `pb_core` grant. `merchant_quota_setting`
rows for the demo merchants (rate 20, burst 40) are added to the seed, which T04 had to defer.

## 4. Components

### 4.1 `application` module

- `payment/RequestPaymentCommand(UUID invoiceId, String methodToken, UUID idempotencyKey)`, normalized and validated.
- `payment/PaymentAdmission`, sealed: `Accepted(paymentId, status, aggregateVersion, replayed)` and `Rejected(Code code, Integer retryAfterSeconds, List<FieldProblem> fieldProblems)` (`Rejected.of(code, retryAfter)` leaves the problems null).
  Codes: `VALIDATION_ERROR`, `NOT_FOUND`, `IDEMPOTENCY_CONFLICT`, `INVOICE_NOT_ELIGIBLE`, `RATE_LIMITED`, `TEMPORARILY_UNAVAILABLE`.
- `payment/AdmitPayment`: the use case (section 5).
- `port/AdmissionStore`:
  - `Optional<Replay> findReplay(merchantId, idempotencyKey)` returning payment id, request hash, status, aggregate version.
  - `AdmissionResult admit(AdmissionRequest)` running the transaction. Result: `Created`, `Duplicate(Replay)` (key race lost; the name `Replay` collides with the `findReplay` record), `InvoiceNotFound`,
    `NotEligible`, `Transient` (deadlock or lock wait).
  - `Optional<MethodChoice> resolveMethod(merchantId, token)` from `method_token`, `method_catalog`, `merchant_method` (all enabled).
- `port/AdmissionGate`: `Optional<Closed> check(merchantId)` (empty when open; `Closed` carries code and retryAfterSeconds); `port/SystemFlags` reads the two DB flags
  (cached `paybridge.admission.flag-cache-ms`, default 1000); `port/ScenarioRunResolver`: `String runFor(merchantId)`;
  `port/ConsistencyCheck`: the DMR recovery queries A to C behind a port, with a MySQL adapter, so `core-app` contains no SQL.
- `ActorContext` already exists in core-app (T05); a copy at the `application` layer would duplicate it, so admission takes plain
  `merchantId`, `userId`, `correlationId` values.

### 4.2 `adapter-mysql` module

- `MysqlAdmissionStore` implements `AdmissionStore`. `MysqlSystemFlags` implements `SystemFlags`. `MysqlConsistencyCheck` implements `ConsistencyCheck`.
- `admit` first runs a reject-only, unlocked pre-check: `findReplay` (a hit is `Duplicate`), then a plain consistent read of the invoice
  pointer (`active_payment_id` set is `NotEligible`, no reservation inserted). Only then does the DMR transaction run, unchanged. The `FOR UPDATE`
  read inside it stays authoritative; the pre-check only rejects. Without it, concurrent same-key requests deadlocked on `ux_payment_request`
  (see section 5). The transaction runs under `PROPAGATION_REQUIRES_NEW`, and the audit store (`MANDATORY`) joins it.
- The `PaymentRequested` event payload is a `PaymentEventEnvelope` serialized with Jackson; payload keys `invoiceId`, `invoiceReference`,
  `methodCode`, `amountMinor`, `currency`, `providerKey` (DMR section 4). No credentials, no method token.
- `MysqlPaymentStore` (T04) gains no write methods; reads for `payment(id)` stay there.

### 4.3 `core-app` module

- `graphql/PaymentDataFetcher`: `requestPayment` (`@auth OPERATOR`, audited) and `payment(id)` (`@auth VIEWER`).
- Result mapping: `PaymentAccepted { payment, replayed }` or `OperationProblem { code, message, retryAfterSeconds }`.
  `Payment` uses decimal strings for `amountMinor` and `version` (the business `aggregate_version`, never the lock counter).
  `attempts` is `[]` and `uncertainty` is `null` until T08 and T12.
- `payment(id)` loads the payment with the merchant of the actor in the query; a foreign or missing id returns `null`.
- `graphql/GraphQlLimits`: depth 8, complexity 300 (1 per selected field, plus 5 per list field times `ceil(limit/25)`), document 8 KiB. Depth and complexity are graphql-java instrumentations (HTTP 200 with an `errors` entry); document size is HTTP 413 from a
  `WebGraphQlInterceptor` with highest precedence, before parsing.
- `admission/InvoicePointersHealthIndicator`: runs the `ConsistencyCheck` port (DMR recovery queries A to C) once at startup of the `api` role and exposes a health
  contributor `invoicePointers`. Rows found mean readiness DOWN and an error log with counts only. A failed run (for example an empty schema) is
  caught and reported DOWN with `check=failed`; the check does not run again, so readiness stays DOWN until restart.
- Properties (all DMR section 10): `paybridge.admission.enabled`, `.lock-wait-timeout-seconds`, `.method-token-max-length`,
  `.default-scenario-run-id`, `.flag-cache-ms`.

## 5. Data flow

```
requestPayment(input)
  authorize                                  (T05: OPERATOR in the signed merchant)
  command = validate(input)                  UUIDs; token <= 128 chars and on the allow-list; else VALIDATION_ERROR
  hash = SHA-256( "REQUEST_PAYMENT" | invoiceId | methodToken )  canonical UTF-8, fields separated by a newline
  replay = store.findReplay(merchant, key)   autocommit, before every gate (the store repeats this and an unlocked pointer read as a reject-only pre-check)
     equal hash   -> Accepted(replayed = true), current status and aggregate_version
     other hash   -> Rejected(IDEMPOTENCY_CONFLICT)
  gates, in this order: paybridge.admission.enabled, flag admission.maintenance = ON, flag admission.capacity = CLOSED,
     AdmissionGate (quota, capacity)         -> Rejected(TEMPORARILY_UNAVAILABLE | RATE_LIMITED, retryAfterSeconds)
  method = store.resolveMethod(...)          unknown or disabled token -> VALIDATION_ERROR
  run = scenarioRunResolver.runFor(merchant)
  repeat up to 3 times:
     result = store.admit(...)               one writer transaction (below)
     Transient -> sleep jitter, retry
  Created -> Accepted(paymentId, REQUESTED, 1, false)
```

The transaction (statement order is the lock order, DMR section 2):

1. `START TRANSACTION` (`REQUIRES_NEW`), then `SET SESSION innodb_lock_wait_timeout = 3` as the first statement inside the Spring transaction; the previous value is restored in `beforeCompletion`, so the pooled connection is left clean.
2. `INSERT payment_request` (reservation). Error 1062: roll back, read the original in a new transaction, return it as `Duplicate`.
3. `SELECT … FROM invoice WHERE id = ? AND merchant_id = ? FOR UPDATE`. No row: `InvoiceNotFound` (rollback). `active_payment_id` not null: `NotEligible` (rollback).
4. `INSERT payment` (REQUESTED, `aggregate_version` 1, `version` 0, `provider_key` `pbk-<id>`, `scenario_run_id`, amount and currency copied from the invoice).
5. `UPDATE invoice SET active_payment_id = ?, version = version + 1 WHERE active_payment_id IS NULL` (expects 1 row).
6. `INSERT payment_event` (STATE, `PaymentRequested`, aggregate version 1).
7. `INSERT outbox` twice: `COMMAND` to `paybridge.commands` / `payment.collect.v1`, and `STATUS` to `paybridge.events` / `payment.status.v1`.
8. `INSERT audit_action` (`PAYMENT_ADMITTED`, actor `USER`, target the payment).
9. `COMMIT`. Any failure before this rolls back everything, including the reservation.

Error mapping: 1062 on `ux_payment_one_active` is a guard that should be unreachable and maps to `INVOICE_NOT_ELIGIBLE`;
1205 and 1213 are `Transient`; any other SQL error rolls back and is a 500-class failure with a sanitized log, never a half-created payment.

## 6. Error handling and non-disclosure

- An invoice of another merchant, or a missing one, is `NOT_FOUND` with the same message as any other missing invoice.
- `payment(id)` of another merchant returns `null`.
- Rejection messages are fixed strings; they never include ids, hashes, amounts or tokens.
- The idempotency key reservation is scoped by merchant and operation, so the same key in two merchants is two requests.

## 7. Testing

Each behavior is written as a failing test first.

Unit (`application`, fakes only): validation table; hash stability and sensitivity to each of its three inputs; replay before gates;
gate order; retry limit and jitter bound; `Rejected` never carries a payment id.

Integration (MySQL container, and the real core over HTTP for the GraphQL ones):

| Test | Proves |
|---|---|
| AdmissionReplayIT | Identical repeat returns the same payment and current state; replay works while a gate is closed |
| PayloadMismatchIT | Different token or invoice for the same key is `IDEMPOTENCY_CONFLICT` |
| AtomicAdmissionRollbackIT | A forced failure at each statement leaves no payment, reservation, event, outbox or audit row |
| AdmissionConcurrencyIT | 50 threads, 5 keys, one invoice: exactly one payment row, the rest replays or `INVOICE_NOT_ELIGIBLE` |
| ConcurrentEligibilityIT | Eligibility is decided under the invoice lock |
| OneActivePaymentIndexIT | With the lock bypassed (raw SQL), the unique index still refuses a second active payment |
| AdmissionRejectionIT | Disabled switch, maintenance flag, capacity flag, quota stub: each rejects and creates nothing |
| AsyncAcknowledgementIT | The response is REQUESTED with aggregate version 1, no provider call, two outbox rows PENDING |
| InvoiceValidationIT | Amount and currency from the invoice; other merchant is `NOT_FOUND`; unknown and over-long tokens rejected |
| ScenarioRunStampIT | The configured run is stamped and a later setting change does not alter an admitted payment |
| GraphQlLimitsIT | Depth 9, complexity over 300 and an over-size document are rejected before execution |
| PaymentQueryIsolationIT | `payment(id)` of another merchant is `null` and indistinguishable from a missing id |
| SchemaConsistencyIT | The startup check reports a planted bad pointer and stays quiet on clean data |

`TableGrantsIT` and `FlywayMigrationIT` are updated for V003 and V004. The new migrations are also applied to the lab MySQL in the kind cluster.

## 8. Risks

- Lock wait inflating the connection pool: the 3 second timeout, the 8-connection API pool, and mapping timeouts to `TEMPORARILY_UNAVAILABLE`.
- The lab chart must configure `innodb_lock_wait_timeout` low (plan risk note); the session setting in step 1 is the authoritative control.
- D4 means a merchant cannot yet select a scenario for a payment through the API; local tests use the configured default.
- The admission stub gates must not ship enabled in a hosted profile after T15 and T18; those tasks replace them and add tests that fail if a stub remains wired.
