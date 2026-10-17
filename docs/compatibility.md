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

## T03: platform prerequisites (verified 2026-10-16, local kind cluster created from nothing by `scripts/cluster-local.sh create`)
Pins are in `ops/toolchain.lock.yaml` (`platform:`). Installed versions are recorded in `artifacts/T03/cluster-versions.json`.

| Component | Pin | Result |
|---|---|---|
| Kubernetes (kind node image, by digest) | v1.36.4 | 3 nodes Ready |
| kind / helm / kubectl client | v0.33.0 / v4.3.1 / v1.36.1 | checksum-verified downloads in `.tools/bin` (git-ignored) |
| Calico (tigera-operator + `crd.projectcalico.org.v1` chart) | v3.33.0 | NetworkPolicy enforced, 26 of 26 paths as specified |
| Gateway API standard CRDs | v1.6.3 | vendored manifest, sha256 checked |
| NGINX Gateway Fabric | 2.8.0 | GatewayClass `nginx`; weighted HTTPRoute, RateLimitPolicy, ClientSettingsPolicy all work |
| metrics-server | chart 3.14.0 (`--kubelet-insecure-tls`, local only) | metrics APIService Available |
| KEDA | 2.21.0 | external metrics APIService Available (scaler trigger names still to confirm in T18) |
| kube-prometheus-stack | chart 92.9.0 | pinned only; installed with `--with-monitoring`, not exercised |

### Findings that change the documents (TIS/POR amendments to apply)
- **Calico 3.32+ ships its CRDs in a separate chart.** The install is `helm template crd.projectcalico.org.v1 | kubectl apply --server-side`, then the operator chart.
- **Public gateway data plane must run on the ingress node.** With Calico VXLAN on kind, a NodePort request that has to hop to a pod on another node does not return. The chart now sets `gateway.console.nodeSelector` (`paybridge/ingress: "true"`) and `externalTrafficPolicy: Local`; the latter also preserves the client address that per-client rate limiting needs.
- **Data plane Service name is `api-internal-nginx`** (and `console-public-nginx`); the weighted route works at `http://api-internal-nginx.paybridge.svc.cluster.local:8080/graphql`. The Apollo base URL in POR 4.2 is therefore confirmed.
- **RateLimitPolicy / ClientSettingsPolicy** exist as `gateway.nginx.org/v1alpha1`. Generated config: `limit_req_zone ... rate=50r/s`, `limit_req ... burst=100 nodelay`, `client_max_body_size 1m`, `keepalive_timeout 60s`. A 600-request parallel burst: 146 served, 454 rejected with 503; a 2 MiB body gets 413.
- **Weighted routing** at 50/50 gave 31/29 over 60 requests.
- **Seed Job** is the one pod that mounts its ServiceAccount token (it updates its Secret). Kubernetes cannot restrict `create` by resource name, so the Role has no `create`; the installer must pre-create an empty `paybridge-demo-accounts`.
- **Table-level grants** for `pb_core` and `pb_metrics` cannot be issued before the tables exist; the lab bootstrap Job creates schemas and accounts only, and T04's migration step must apply the table grants.
- **HTTPRoute request timeout** (30 s in POR 3.6) is not configured; T21 decides whether the release honours the Gateway API `timeouts` field.
- MongoDB lab account is created as the instance root user (`authSource=admin`); a hosted profile should create a per-database user.

## T04: MySQL adapter, identity and invoice schema, seed (verified 2026-10-17)
MySQL 8.4.11 (the digest pinned in the lock file), Hibernate 6.6.53 with `ddl-auto=validate`, Flyway 11.7.2, Connector/J 9.7.0. 101 tests pass (`artifacts/T04/test-summary.json`); 44 of them are new, 38 of those need Docker. The migrate role also ran against the lab MySQL in the kind cluster: 2 migrations applied, then 0 on the rerun (`artifacts/T04/lab-migrate-run.txt`, `lab-schema.txt`).

### Findings that change the documents (DMR/POR/TIS amendments to apply)
- **Plan and DMR disagree on V001.** The plan says `method_catalog`, `method_token` and `merchant_method` arrive with V004. DMR puts them, and `audit_action`, in V001, and the seed needs them now. V001 follows DMR. V004 keeps `reconciliation_note`, `merchant_quota_setting`, `dead_letter_record` and `system_flag`.
- **`pb_migrate` needs `GRANT OPTION` on the `paybridge` schema.** Table-level grants for `pb_core` can only be issued after the tables exist, and the migrate role is the only thing that runs afterwards. The lab bootstrap Job now grants `ALL PRIVILEGES ... WITH GRANT OPTION` on `paybridge.*` only. The grants live in `modules/adapter-mysql/src/main/resources/db/grants/table-grants.sql` (not `ops/sql/bootstrap-users.sql` as T03 expected) and the migrate role applies them after Flyway. `TableGrantsIT` fails when a table has no `pb_core` grant, so a later migration cannot forget its grants. Hosted runbooks must give `pb_migrate` the same grant option.
- **The seed generates the demo passwords; the installer does not.** DMR section 3 says the installer generates them and the seed reads them. The plan says the seed generates them and writes the Secret through the Kubernetes API, and the T03 RBAC (Role without `create`, pre-created empty Secret) is built for that. T04 follows the plan: only accounts created in this run get a password, the sink is called before the rows are inserted, and an existing account is never touched. `scripts/install-local.sh` (T19) must pre-create the empty Secret and must not write passwords. DMR/POR text on this point needs amending.
- **Seed profile settings are `paybridge.seed.profile` (`base`|`demo`) and `paybridge.seed.credentials.sink` (`kubernetes`|`file`).** The chart's seed Job now sets `PAYBRIDGE_SEED_PROFILE` and `PAYBRIDGE_SEED_CREDENTIALS_SINK`.
- **`merchant_quota_setting` is not seeded yet.** Its table arrives in V004 (T16). The demo seed adds the two quota rows then.
- **Spring Boot's Flyway auto-configuration is disabled** (`spring.flyway.enabled=false`), otherwise every application role would migrate with its own account at startup.
- **MySQL returns `BIGINT` for `GET_LOCK`.** Read it as a number, not an `Integer` cast.
- **Check constraint violations surface as `UncategorizedSQLException`**, not `DataIntegrityViolationException`. Code that handles a rejected invoice must not rely on the second type.
- **`DATETIME(6)` rounds, `Instant` does not.** The adapter clock is truncated to microseconds so a returned record equals the stored row.
- **`CHAR(36)` columns need `@JdbcTypeCode(SqlTypes.CHAR)`** or schema validation sees `varchar`.

### Not verified
- The seed Job has not run in the cluster (no application image yet). The Kubernetes Secret sink is tested against a local fake HTTP server only, including the request path, merge-patch content type, bearer header and that no value reaches an exception message. Its in-cluster TLS setup is untested.
- The readiness indicator is unit-tested through the health contributor, not through an HTTP `/actuator/health/readiness` call (no web server in core-app yet).

## T05: identity service and core security (verified 2026-10-17)
DGS 10.6.0 with Spring GraphQL on Boot 3.5.16, Lettuce (Spring Data Redis 3.5.13) for the login throttle, bcrypt cost 12 through Spring's `DelegatingPasswordEncoder`. 180 tests pass (`artifacts/T05/test-summary.json`); 79 are new: 40 in `core-app` and the rest against real MySQL 8.4.11, Redis 7.4.11 and the real core application over HTTP. Introspection is off.

### Findings that change the documents (API/DMR/POR amendments to apply)
- **Spring GraphQL logs every document together with its variables at DEBUG.** That prints passwords and temporary credentials. `PasswordResetIT` caught it with a DEBUG log capture; `application.yaml` now pins `org.springframework.graphql`, `com.netflix.graphql.dgs` and `graphql` to INFO in every profile. No hosted profile may lower those loggers. API 5.2 already says credential fields must stay out of tracing; it should also name request logging.
- **The login throttle runs in the core's `authenticate`, not in the console.** DMR section 10 lists the property under the console role. The core is the only component that decides on a password, and the client address must reach it somehow, so the console sends it as the header `X-Client-Address` (not a token claim, because the claim list is closed). The key is `pb:login:fail:{username}:{clientAddress}`, 5 failures in 15 minutes, `RATE_LIMITED` with `retryAfterSeconds`, `TEMPORARILY_UNAVAILABLE` when Redis is down. T10 must send the header and may keep its own edge limit as well. The wrong-current-password branch of `changePassword` counts against the same key.
- **`membership` needs `DELETE` for `pb_core`.** Removing a role is a delete; the DMR grant listing had no `DELETE` on this table. Memberships are not financial rows and every change is audited. `table-grants.sql` now grants it; DMR section 2 needs the same line.
- **Service context verification is hand written** (about 180 lines on the JDK's `Signature` and Jackson) instead of adding a JOSE library. It rejects any algorithm but RS256, any `typ` but JWT, an unknown `kid`, any claim outside the contract list (so `role`, `admin`, `permissions` are refused), an array `aud`, a lifetime over 30 s, and keys under 2048 bits. A token signed with the previous key verifies while both keys are in the JWKS file; the file is re-read when it changes or an unknown `kid` appears, at most once a second.
- **Denials are typed by the field's return type.** A union result gets an `OperationProblem`, a nullable lookup gets `null` (not visible), a non-null list becomes a GraphQL error. A root field without `@auth` is refused, and `SchemaAuthDirectiveTest` fails the build if one appears. Only `setupStatus`, `bootstrapAdministrator` and `authenticate` are anonymous (also tested).
- **The operation allow-list is off by default** (`paybridge.security.service-context.allowed-operations` empty). Hosted profiles must set it to the generated Apollo operation names; T10 or T19 produces that list.
- **Verification failures return HTTP 401 with the generic reason `Unauthorized`.** The reason goes to the log only.
- **Bootstrap answers:** a consumed state or an existing administrator gives `SETUP_ALREADY_COMPLETED`; a wrong, expired or missing token gives `BOOTSTRAP_TOKEN_INVALID` with the same message; password and username problems are reported only to a caller holding a valid token, and leave setup open.
- **An account with `mustChangePassword` can call only `me`, `changePassword` and `myMerchants`.** Every other operation answers `PASSWORD_CHANGE_REQUIRED`.

### Not done in T05 (disclosed)
- `scripts/setup-admin.sh` is not written. It calls the console's `POST /api/setup/admin`, which arrives in T10. `scripts/bootstrap-token.sh` is written and its Secret handling was run against the kind cluster; re-running the seed Job needs the application image (T19).
- Query depth 8, complexity 300, document size 8 KiB, 64 KiB body and 8 s execution limits from API 5.4 are not implemented yet; they have no deliverable in T05 and need an owner (proposed T21).
- Payment, invoice and case lookups by id cannot be tested for non-disclosing isolation yet because those resolvers arrive in T07 onward. The merchant check they will use (`Authorizer`, `ActorContext.merchantId`) is tested through the authorization layer and the user and merchant lookups.
- The Apollo Java 4.4.3 keep-or-replace decision is still open; T05 does not use the client.
- The seed and migrate Jobs, and the Redis throttle against the cluster's Redis, have not run in the kind cluster (no application image).

## T06: provider simulator and adapter (verified 2026-10-17)
Tomcat 10.1 (Boot 3.5.16) for the simulator, `java.net.http.HttpClient` for the adapter and the webhook sender, MySQL 8.4.11 for `paybridge_sim`. 274 tests pass in the whole build (`artifacts/T06/test-summary.json`); the five scenarios are transcribed in `artifacts/T06/scenario-transcript.txt`. The simulator boot jar was also started against the lab MySQL in the kind cluster with the environment variable names the chart renders: it migrated `paybridge_sim`, reported readiness UP and accepted a charge (that test row was deleted again).

### Findings that change the documents (API/DMR/POR amendments to apply)
- **"Drop the response" needs a Tomcat hook.** A servlet cannot close a socket without writing. The simulator marks the request and a Tomcat `Valve` sends `ActionCode.CLOSE_NOW` before any byte is written, so the client sees a connection that ended with no answer. This ties the simulator to Tomcat (the Boot default); a switch to Jetty or Undertow needs another implementation.
- **The DMR scenario tables did not carry what the API reports.** The API shows `usedCount`, `maxUses` and the state `EXHAUSTED`; DMR `sim_scenario_run` had neither column. V001 adds `max_uses` and `used_count`, adds `scenario_run_id` to `sim_key_counter` (so a key is counted once and a retry cannot use the run twice) and adds `ix_sim_charge_updated`.
- **The request hash includes `scenarioRunId`.** API 8.2 says the run is immutable for the key; DMR's hash list omitted it. A key reused with another run is a `409 idempotency_conflict`.
- **The operations listing follows the API, not the DMR.** API 8.3: by `updatedAt`, newest first, at most 100 per page, opaque cursor. DMR section 9 described `created_at` ascending with limit 500. Restore comparison works with either; the code implements the API.
- **A decline is HTTP 200, a new success is 201, a new pending transfer is 202.** API 8.2 allowed "201 or 200" for a decline; DMR says 200. The adapter accepts all of them.
- **Provider reference format is `sim_ch_` plus 20 hex characters** (DMR). The API examples show `simch_...`; examples only.
- **`spring.config.name=simulator`.** The simulator's settings file is `simulator.yaml` so that it cannot be shadowed by another module's `application.yaml` on a shared classpath (it was, in the integration tests). The `main` method sets the name; any other launcher must too.
- **Time zones broke the first version.** `java.sql.Timestamp.valueOf(LocalDateTime)` shifts by the JVM's zone before the driver converts again. All simulator times are now `LocalDateTime` in UTC, which is written as is. The chart's JDBC URL does not need a time zone parameter for this.
- **Chart fixes found while checking the variable names:** the simulator webhook target is `/internal/webhooks/simulator` (the skeleton had `/webhooks/provider`), and `PAYBRIDGE_SIMULATOR_WEBHOOK_SECRET` is now rendered from `paybridge-service-keys` entry `webhook-hmac-current`. `PAYBRIDGE_SIMULATOR_DATASOURCE_POOL_MAX_SIZE` binds to `paybridge.simulator.datasource.pool.max-size`; a unit test checks every variable the chart sets for the simulator.
- **The worked webhook example is correct:** the 286 byte body and signature `dc8fa64a…` reproduce exactly, in both the simulator and `WebhookSignatureVerifier`.

### Not done in T06 (disclosed)
- **No separate simulator administrator identity.** One bearer token guards charge, lookup, scenario and listing endpoints; the plan asked for an admin identity on the scenario endpoints. Network isolation (ClusterIP, NetworkPolicy) is the second control. Proposed: split the token when T19 creates the Secrets.
- **The core webhook route does not exist yet** (T11). The simulator is tested against a stand-in receiver; the signature check on the receiving side is `WebhookSignatureVerifier`, tested against the documented vector.
- **Cross-pod behavior is by design but was exercised with one simulator.** `SKIP LOCKED` workers and the 30 second visibility timeout are in the code; two simultaneous pods were not run.
- **`ResponseLossIT` and the reconciliation case reasons** (`PROVIDER_IDEMPOTENCY_CONFLICT`, `PROVIDER_REJECTED`) belong to the executor and reconciliation tasks (T08, T12). T06 delivers the adapter classes those tasks react to.
