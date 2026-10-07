# ratekit implementation plan (2026-10-03)

Design inputs: `docs/research/2026-10-03-kickoff.md` and the decisions in `AGENTS.md`.
Stack: Java 21, Spring Boot 3, Maven multi-module, Kafka, PostgreSQL. Three services: `ingest`, `rating`, `billing`. Prepaid hard stop. At-least-once delivery with idempotent consumers. Apache-2.0.

## Status (2026-10-03)

Tasks 0 to 14 are done. What was added beyond the plan, because the work showed it was needed: ADR 0003 (package structure, after a review of the first flat layout), 0004 (retry and dead-letter policy), 0005 (invoicing) and 0006 (metrics); a REST API and an optional scheduler for invoice runs; `BillingPeriod` moved to `common` so rating and billing share one definition of a month; a benchmark of balance-deduction strategies. The CI pipeline ran green on GitHub (build and test, then three image builds). Measured results, with the machine and its limits, are in `docs/perf.md`.

## Review follow-ups (2026-10-07)

A review after task 14 found gaps the README did not list. Each was closed in its own commit with tests, a deliberate mutation check and, where it was a decision, an ADR: invalid tariff rows dead-lettered at once (ADR 0004 amendment); an event time window at ingest (0007); account, top-up (0008) and tariff (0009) APIs in rating; invoicing only after rating has caught up (0010); a running usage counter (0011); a tariff cache (0012); migrations in either order (0013); retention of refused events (0014); a shared API key (0015); bounded ids, actuator healthchecks, a real pre-commit hook and Dependabot. Still open: adjustment invoices for late usage, cache staleness across several rating instances, per-caller identities, separate databases, Kafka TLS and SASL.

## How we work

- The owner is learning. One task at a time: explain the why first, implement, show the verify output, then wait for review before the next task.
- Each task ends in one small commit (Conventional Commits, no emoji, no attribution trailers).
- Nothing outward-facing without asking: no GitHub repo creation, push or release until the owner says so.

## Out of scope

Diameter or session charging, reserve-and-commit, payments, subscriptions and plans, Debezium and outbox, multi-tenancy, a Debezium-style CDC, a frontend dashboard, GCP and Terraform. Revisit only after task 14.

## Facts checked on this machine (2026-10-03)

- JDK: only OpenJDK 25.0.1 is installed. Target is 21, so a JDK 21 is needed (task 0).
- Maven: not installed.
- Docker: not installed. `podman` and `podman-compose` are installed. Compose files stay Docker-compatible. Testcontainers on Podman needs socket configuration: **confirm the exact settings before task 7**.

## Phase A: foundation

### 0. Toolchain
- **Files:** none in the repo.
- **Do:** (1) Ask the owner before installing. (2) Install a JDK 21 and Maven with `brew`. (3) Check that `podman machine` is running and can pull images.
- **Verify:** `java -version` shows 21 in the project shell (via `JAVA_HOME`), `mvn -v` runs and reports Java 21, `podman run --rm hello-world` succeeds.
- **Done when:** the three commands above work and the JDK 21 setup is written in `AGENTS.md` under a short "Commands" heading.

### 1. Maven multi-module skeleton
- **Files:** create `pom.xml` (parent), `common/pom.xml`, `ingest/pom.xml`, `rating/pom.xml`, `billing/pom.xml`, `.gitignore`, `LICENSE` (Apache-2.0), `README.md` stub.
- **Do:** parent POM sets Java 21 and imports the Spring Boot BOM; `common` is a plain library, the other three are Spring Boot apps with a main class and one context-loads test.
- **Verify:** `mvn -q verify` passes from the root.
- **Done when:** four modules build in one command. Commit: `chore: add maven multi-module skeleton`.

### 2. Local infrastructure with Compose
- **Files:** create `compose.yaml`, `docs/specs/local-dev.md`.
- **Do:** PostgreSQL and a single Kafka broker (KRaft mode, no ZooKeeper). Pin image tags; **confirm current tags and Kafka env var names with `--help` or the image docs before writing them**. Add health checks.
- **Verify:** `podman compose up -d` (or `podman-compose`), then create and list a topic with the Kafka CLI inside the container, and run `psql -c 'select 1'`.
- **Done when:** both services are healthy and the verify commands are recorded in `docs/specs/local-dev.md`.

## Phase B: first vertical slice (event in, charge out)

### 3. Event contract and money rules (`common`)
- **Files:** `common/src/main/java/.../UsageEvent.java`, `Money` rules class, tests.
- **Do:** `UsageEvent` as a record (eventId, accountId, meter, quantity, occurredAt). Money uses `BigDecimal` with one rounding rule, written in an ADR (`docs/adr/0001-money-and-rounding.md`).
- **Verify:** `mvn -q -pl common test`: boundary tests for rounding and for invalid quantity.
- **Done when:** tests pass and the ADR exists.

### 4. Ingest endpoint with OpenAPI
- **Files:** `ingest/.../EventController.java`, `EventPublisher.java`, config, tests.
- **Do:** `POST /v1/events` validates and publishes to topic `usage-events`, keyed by `accountId` (same account stays in one partition, so ordering and balance updates are serial per account). Add springdoc for OpenAPI/Swagger UI; **confirm the springdoc version that matches the Spring Boot version before adding it**.
- **Verify:** an integration test publishes through the endpoint and consumes the record from Kafka; `curl` on the running app returns 202; Swagger UI page loads.
- **Done when:** the test passes and `/v3/api-docs` returns the spec.

### 5. Rating schema (Flyway)
- **Files:** `rating/src/main/resources/db/migration/V1__init.sql`.
- **Do:** tables `accounts`/`balances`, `tariffs` (with `effective_from`), `processed_events` (unique `(account_id, event_id)`), `charges`.
- **Verify:** migration applies on a clean Testcontainers Postgres; schema test asserts the unique constraint.
- **Done when:** the migration test passes.

### 6. Tariff logic as pure Java
- **Files:** `rating/.../domain/` (no Spring imports), unit tests.
- **Do:** price models as strategies: flat, tiered, free-quota-then-flat. Versioned lookup by event time.
- **Verify:** pure unit tests, including tier boundaries and version switch at the effective date.
- **Done when:** the domain package compiles without any Spring dependency (checked by the test or a package rule) and tests pass.

### 7. Rating consumer with idempotency
- **Files:** `rating/.../KafkaEventListener.java`, repository, integration test.
- **Do:** consume `usage-events`, insert into `processed_events` with `ON CONFLICT DO NOTHING`; only a new insert continues to rating.
- **Verify:** Testcontainers test sends the same event twice and asserts exactly one charge row.
- **Done when:** the duplicate test passes. **Confirm Testcontainers-on-Podman settings first.**

### 8. Prepaid balance deduction under concurrency
- **Files:** `rating/.../BalanceRepository.java`, concurrency test, `docs/adr/0002-balance-deduction.md`.
- **Do:** first version is one atomic `UPDATE ... SET balance = balance - :x WHERE balance >= :x`; zero rows updated means rejected. Record rejected events in a `rejected` table or topic.
- **Verify:** test fires N parallel deductions against a small balance; final balance is never negative and accepted plus rejected equals N.
- **Done when:** the test is stable over repeated runs and the ADR states why this beats read-modify-write.

### 9. Retry and dead-letter topic
- **Files:** rating error-handler config, test.
- **Do:** bounded retries with backoff, then publish to `usage-events.dlq`.
- **Verify:** a poison message ends in the DLQ topic and the consumer keeps processing later events.
- **Done when:** the test passes.

## Phase C: billing

### 10. Invoice run
- **Files:** `billing/...`, migration, ADR on Spring Batch vs scheduled partitioned job.
- **Do:** read charges per `(account, period)`, write one invoice; the run is idempotent per `(account, period)`.
- **Verify:** run twice, assert one invoice; period edge test around midnight and time zone.
- **Done when:** both tests pass.

## Phase D: delivery

### 11. Containerise all services
- **Files:** a Dockerfile per service, extend `compose.yaml`.
- **Do:** multi-stage builds; services reach Kafka and Postgres through the compose network.
- **Verify:** `podman compose up` starts the full stack; a `curl` event ends as a charge row in Postgres.
- **Done when:** the end-to-end command sequence is in `docs/specs/local-dev.md`.

### 12. CI pipeline (GitHub Actions)
- **Files:** `.github/workflows/ci.yml`.
- **Do:** build and test on push and pull request, build images. Repo creation and the first push need the owner's explicit go-ahead.
- **Verify:** the workflow file lints locally if a linter is available; after the owner pushes, the first run is green.
- **Done when:** a green run exists.

### 13. Load test and observability
- **Files:** `load/k6/*.js`, actuator and Micrometer config, `docs/perf.md`.
- **Do:** k6 script against ingest; record events per second and end-to-end lag on this machine. Alternative lock strategies from task 8 are compared here.
- **Verify:** numbers come from an actual run and are saved with the command and machine description.
- **Done when:** `docs/perf.md` holds real numbers.

### 14. README and ADR review
- **Files:** `README.md`, `docs/adr/`.
- **Do:** architecture diagram, how to run, design decisions.
- **Verify:** a fresh clone runs the stack by following the README alone.
- **Done when:** every claim in the README maps to a file, test or pipeline run.
