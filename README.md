# PayBridge

Payment-orchestration backend on Java 25 and Spring Boot. PayBridge admits payment requests idempotently, guarantees one active payment per invoice, talks to billing providers through a port, and recovers from lost responses by looking the payment up rather than charging again.

**Status:** under construction, built task by task from the design set (T01–T06 done, T07 admission in design). Backend only; no frontend yet.

## What it does

- **Idempotent admission.** A scoped idempotency key is reserved first, the invoice is locked, and the payment, event, outbox and audit rows commit in one transaction. The provider is never called in this path.
- **One active payment per invoice.** Eligibility is checked under a row lock, with a unique index as a second line of defence.
- **Provider port.** `BillingProvider` has `charge` and `lookup`, never throws for provider or transport trouble, and classifies every outcome. A deterministic provider simulator with signed webhooks stands in for a real provider.
- **Identity and isolation.** First-run setup, signed service context, merchant-scoped authorization on every GraphQL call.
- **Operable.** Local kind cluster with MySQL, Redis, MongoDB and RabbitMQ, pinned toolchain, dependency locking and verification.

## Stack

Java 25, Gradle 9.8, Spring Boot 3.5, Netflix DGS (GraphQL), Hibernate 6.6, Flyway, MySQL 8.4, Redis 7.4, MongoDB, RabbitMQ, Apollo Kotlin/Java client, Testcontainers, ArchUnit, JaCoCo. Exact pins are in `ops/toolchain.lock.yaml` and `docs/compatibility.md`.

## Layout

```
modules/
  contracts  domain  application      pure model, use cases and ports
  adapter-mysql  adapter-mongo  adapter-rabbit  adapter-provider
  core-app  console-app  simulator-app  apollo-client
  integration-tests
build-logic/   convention plugins
ops/ deploy/ scripts/   toolchain lock, Helm/kind deployment, helper scripts
docs/          compatibility record, runbooks, specs
```

Module boundaries are enforced by ArchUnit (`ModuleBoundaryIT`).

## Build and test

Requires JDK 25 and Docker (integration tests use Testcontainers).

```
./gradlew clean check
```

Adding a dependency needs its lock and checksum refreshed:

```
./gradlew --write-locks --write-verification-metadata sha256 <task>
```

## Documentation

- `docs/compatibility.md` — resolved stack and findings per task
- `docs/runbooks/provider-integration.md` — provider port contract and simulator operation
- `docs/superpowers/specs/` — per-task design specs

## License

Apache License 2.0. See [LICENSE](LICENSE).
