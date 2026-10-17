# Provider integration runbook

Applies to: `BillingProvider` port, `SimulatorBillingProvider`, the provider simulator (`:simulator-app`). Requirement: FR-031.

## What the simulator is, and is not

The simulator is a **test double that behaves like a well mannered provider**. Everything the showcase proves about recovery
(one financial effect per provider key, lookup after a lost response, ordered revisions, signed webhooks) is proven against
these guarantees:

| Guarantee in the simulator | A real provider may instead |
|---|---|
| Keeps every idempotency key for the lifetime of its dataset | Expire keys after hours or days, so a late retry creates a second charge |
| Compares the full request on a repeated key and answers `409 idempotency_conflict` | Ignore changed fields, or reject only some of them |
| Answers a lookup by key from the same table it charges from, immediately | Serve lookups from a replica that lags, or not offer lookup by merchant key at all |
| Numbers revisions 1, 2, 3 per charge | Use timestamps, opaque versions, or none |
| Delivers each event at least once with the same event id and a fresh timestamp | Reorder events, drop them, or change the event id on retry |
| Signs `timestamp + "." + rawBody` with HMAC-SHA256 (`v1=` + hex) | Use another scheme, header names or clock tolerance |
| Takes the charge before answering and can drop only the answer | Fail before or after the effect in ways no flag exposes |

**Do not copy `SimulatorBillingProvider` into a production adapter.** Treat a real provider as a new adapter with its own
contract tests, its own failure table and an entry in this runbook.

## The port contract (what every adapter must honour)

`com.paybridge.application.port.BillingProvider`:

- `charge(ChargeRequest)` and `lookup(LookupRequest)` return exactly one `ProviderResult` and **never throw** for a provider or
  transport problem.
- The provider key is `pbk-<paymentId>` (D-28) and is identical on every attempt of the same payment.
- Classes: `OBSERVED` (success, decline or pending), `IDEMPOTENCY_CONFLICT`, `SCENARIO_NOT_AVAILABLE`, `CLIENT_ERROR`, `NOT_FOUND`
  (lookup only), and `NO_EVIDENCE` with an error class `RETRYABLE_UNAVAILABLE`, `TRANSPORT_UNCERTAIN` or `MALFORMED`.
- A body is evidence only when provider key, amount, currency and status all agree with what was asked and the revision does
  not go backwards. Otherwise it is `MALFORMED`, which callers treat like transport uncertainty.
- A decline is a business outcome, never an HTTP error. A `404` on lookup is *not found at this time*, never a decline.
- Connection refused or connect timeout means nothing was sent (`RETRYABLE_UNAVAILABLE`). A timeout, reset or `5xx` other than
  `503` after sending means the effect is unknown (`TRANSPORT_UNCERTAIN`).
- The adapter performs no SQL and holds no credentials in its `toString`.

## Accepting a new adapter

1. Implement the port in `modules/adapter-provider` (or a new adapter module that depends only on `:application` and `:domain`).
2. Subclass `AdapterCapabilitySuite` for each payment method the adapter serves. All checks must pass against the real
   provider's sandbox or a faithful stub.
3. Add a response classification test table like `ProviderResponseClassificationTest` for every status and body the provider
   can produce.
4. Add a section to this document: idempotency window, lookup semantics and lag, ordering, webhook scheme, rate limits,
   timeouts, and what a payment in `UNKNOWN` needs from an operator.
5. Review the reconciliation delays (`paybridge.reconciliation.delays-seconds`) against the idempotency window. The schedule must
   finish inside the window or reconciliation may repeat a charge the provider has already forgotten.

## Operating the simulator

Everything under `/internal/**` and `/v1/**` needs `Authorization: Bearer <token>` (Secret `paybridge-simulator`, key `api-token`).
The Service is ClusterIP only and no Gateway routes it. Reach it with a port-forward:

```
kubectl -n paybridge port-forward svc/simulator 18080:8080
TOKEN=$(kubectl -n paybridge get secret paybridge-simulator -o jsonpath='{.data.api-token}' | base64 -d)
```

Create a deterministic run for one test (runs expire after 24 hours; `default` always succeeds and cannot be edited):

```
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' localhost:18080/internal/scenario-runs -d '{
  "runId": "run-lost-001", "scenario": "SUCCESS_RESPONSE_LOST", "failFirst": 1, "maxUses": 1 }'
```

| Scenario | Parameters (defaults) | What the provider does |
|---|---|---|
| `SUCCESS` | `webhookDelayMs` | Charge succeeds, revision 1, event `charge.succeeded` |
| `DECLINE` | `declineCode` (`INSUFFICIENT_FUNDS`) | Authoritative decline, HTTP 200, event `charge.declined` |
| `DELAYED_TRANSFER` | `delaySeconds` (3), `finalOutcome` (`SUCCEEDED`), `declineCode` | HTTP 202 `PENDING` revision 1, final state revision 2 after the delay, then the event; survives a restart |
| `TIMEOUT_BEFORE_PROCESSING` | `failFirst` (1), `sleepSeconds` (15), `thenBehavior` (`SUCCESS`) | Sleeps then closes the connection with nothing stored; later calls behave as `thenBehavior` |
| `SUCCESS_RESPONSE_LOST` | `failFirst` (1) | Stores and queues the event, then closes the connection without answering; later calls return the stored charge |

A run that is unknown, expired, deactivated or exhausted is **never** replaced by `default`: the charge is refused with
`422 SCENARIO_NOT_AVAILABLE` and nothing is stored. `maxUses` limits distinct provider keys; repeats of a key already counted
are still served.

### Finding operations after a restore

`GET /internal/operations?from=<iso>&to=<iso>&status=<optional>&limit=<=100>&cursor=<opaque>` lists charges by `updatedAt`,
newest first. After restoring PayBridge from a backup, compare it with the restored `payment.provider_key` values to find
provider operations that exist only on the provider side (the data loss window).

### Webhook delivery

Events go to `paybridge.simulator.webhook.target-url` with `X-PB-Event-Id` (stable), `X-PB-Timestamp` and `X-PB-Signature`
(fresh on every attempt). Defaults: attempts after 0, 1, 2, 5, 15, 60, 300, 300, 300 seconds; after the eighth failure a
delivery is `FAILED` and stays in `sim_webhook_delivery` for inspection. A delivery can occur twice; the core deduplicates by
event id.

## Known simulator limits

- One rule per run created through the API; the database model allows several rules, which only the built in run uses.
- Scenario endpoints accept one bearer token; there is no separate administrator identity yet. Network isolation is the control.
- Sleeping scenarios hold a request thread for `sleepSeconds`.
- No real card data is ever accepted: only the three synthetic method tokens are valid.
