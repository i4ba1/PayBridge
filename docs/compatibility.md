# PayBridge compatibility record (T01)

Verified 2026-10-16 on Windows 11, Docker Desktop 29.8.1. Exact pins: `ops/toolchain.lock.yaml`. Evidence: `artifacts/T01/`.

## Resolved stack
Java 25 (toolchain, `--release 25`, no preview), Gradle 9.8.1, Spring Boot 3.5.16, DGS 10.6.0, Hibernate 6.6.53, Flyway 11.7.2,
Mockito 5.17.0, Testcontainers 2.0.5, JaCoCo 0.8.15, ArchUnit 1.5.1, Apollo Java 4.4.3 (plugin and runtime).
Runtime image: Temurin 25.0.4.1 JRE, digest pinned. The Spring Boot 3.5 line worked on Java 25, so the D-03 fallback was not needed.

## Probe results (all passed)
| Check | Result |
|---|---|
| Compile all modules, `--release 25` | pass |
| Class-file version 69 asserted in test | pass |
| DGS ping query and echo mutation | pass |
| Apollo codegen (Java models from the same .graphqls) | pass |
| Apollo to DGS: typed data, GraphQL error, transport failure kept separate | pass |
| Flyway + JPA on MySQL 8.4.11 | pass |
| RabbitMQ 4 quorum queue publish/consume | pass |
| MongoDB upsert with max revision | pass |
| Redis-backed Spring Session | pass |
| Prometheus scrape on management port 8081 | pass |
| Mockito: final class and record-using service | pass |
| JaCoCo on class-file 69, line and branch counters (40/43 lines, 2/2 branches) | pass |
| ArchUnit rules | pass |
| Layered bootJar, image UID 10001, read-only root, readiness UP in ~8 s | pass |
| Cold Gradle cache rebuild under dependency locking and verification | pass |

## Findings
- **Apollo Java runtime is deprecated upstream.** `apollo-runtime-java` 4.4.3 is the last release. It works on Java 25 here, but T05/T19 should decide
  whether to keep it or wrap the private GraphQL call another way. Pinned for now.
- **Not exercised:** RabbitMQ `delivery-limit` policy (T09), all Kubernetes platform pins (T03). Image tags for those are candidates only.
- The probe's `standalone` profile exists only so the image can boot without stores; it is not production configuration.
- Testcontainers 2.x module names (`testcontainers-mysql` etc.) and package `org.testcontainers.mysql` differ from 1.x.

No later task may change this stack without a new compatibility run.
