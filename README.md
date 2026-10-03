<div align="center">

# ratekit

**Open-source usage metering and rating engine: usage events in, priced charges out.**

[![License](https://img.shields.io/badge/license-Apache--2.0-000?style=flat-square)](LICENSE)
![Java](https://img.shields.io/badge/Java-21-000?style=flat-square&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-000?style=flat-square&logo=springboot)
![Kafka](https://img.shields.io/badge/Kafka-4.3-000?style=flat-square&logo=apachekafka)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-000?style=flat-square&logo=postgresql)
![Maven](https://img.shields.io/badge/Maven-multi--module-000?style=flat-square&logo=apachemaven)

</div>

---

ratekit takes a stream of usage events (an SMS sent, a megabyte used, a minute called), decides what each one costs under the tariff that applied at that moment, and records the charge. It is the core of a prepaid charging and billing system, kept small enough to read in an afternoon.

> **Status: early development.** Ingest and rating work end to end. Prepaid balance deduction, retry and dead-letter handling, invoicing, container images and CI are still to come; see [Status](#status).

## What it is

An event enters through a REST endpoint and is written to Kafka. A rating service reads it, prices it against versioned tariffs and stores the charge in PostgreSQL. The pieces are separate services so each can scale and fail on its own, and Kafka sits between them so a slow or restarted service never loses an event.

```
 usage source
      |  POST /v1/events
      v
 +----------+   key = accountId   +----------- Kafka -----------+
 |  ingest  | ------------------> | topic: usage-events         |
 | producer |                     |  P0 [...]  P1 [...]  P2 [...] |
 +----------+                     +--------------+--------------+
                                                 |  consumer group
                                                 v
                                          +-------------+      +--------------+
                                          |   rating    | ---> |  PostgreSQL  |
                                          |  consumer   |      |  accounts    |
                                          +-------------+      |  tariffs     |
                                                               |  charges ... |
                                                               +------+-------+
                                                                      |
                                                                      v
                                                               +-------------+
                                                               |   billing   |  (planned)
                                                               +-------------+
```

## Modules

| Module | Role | What it does | Port |
| --- | --- | --- | --- |
| `common` | Shared library | The event contract (`UsageEvent`), the money rules (`Money`) and topic names. Plain Java, no Spring, so every service agrees on the same types and the same rounding. | none |
| `ingest` | Producer, the front door | `POST /v1/events` validates an event and writes it to Kafka, keyed by account. Answers `202` only after Kafka has acknowledged the write. Serves the OpenAPI spec and Swagger UI. | 8081 |
| `rating` | Consumer, the pricing core | Reads events from Kafka, skips duplicates, finds the tariff version in force at the event's time, computes the charge and stores it. Owns the database schema. | 8082 |
| `billing` | Invoicing | Will turn a period's charges into invoices. Currently an empty Spring Boot skeleton. | 8083 (planned) |

## How an event flows

1. A client sends `POST /v1/events` with `eventId`, `accountId`, `meter`, `quantity` and `occurredAt`.
2. `ingest` validates it. An invalid event gets `400` and nothing is written.
3. `ingest` publishes it to the `usage-events` topic with `accountId` as the message key and waits for the broker to acknowledge. Only then does it return `202`. If Kafka does not answer, the client gets `503` and retries.
4. Kafka stores the event in one partition. All events of one account share a partition, so they are read in order, while different accounts are processed in parallel.
5. `rating` reads the event and tries to record `(accountId, eventId)` in `processed_events`. If that row already exists the event is a redelivery and is skipped.
6. Otherwise `rating` loads the tariff versions for the meter, picks the one in force at `occurredAt`, adds up what the account already used this month, and prices the event.
7. The charge is stored. Steps 5 to 7 run in one database transaction, so a failure leaves no half-processed event behind.

## Highlights

| | Feature | How it works |
| --- | --- | --- |
| 1 | **At-least-once with idempotent consumers** | Kafka may deliver a message twice. The primary key `(account_id, event_id)` on `processed_events` makes the second attempt insert zero rows, so it is detected and skipped. ratekit never claims exactly-once. |
| 2 | **Ordering per account** | The account id is the Kafka key, so one account's events stay in one partition and are consumed in order. |
| 3 | **Versioned tariffs** | A tariff is never edited. A price change is a new row with a later `effective_from`, so an old event is always priced with the tariff that applied when it happened. |
| 4 | **Three price models** | Flat, graduated tiers, and a free quota followed by a flat rate. Each is a strategy behind one `PriceModel` interface. |
| 5 | **Usage accumulates across events** | Free quotas and tiers count the whole calendar month (UTC), so an event that crosses a quota or a tier boundary is split correctly. |
| 6 | **Exact money** | `BigDecimal` only, scale 4, half-even rounding, rounded once per charge. Unit prices keep full precision. See [ADR 0001](docs/adr/0001-money-and-rounding.md). |
| 7 | **Safety lives in the database** | `CHECK (balance >= 0)`, unique and foreign keys reject bad data even if application code is wrong. |
| 8 | **Framework-free domain** | The pricing logic has no Spring, Kafka or JDBC imports; a test fails the build if one appears. |
| 9 | **Documented API** | OpenAPI spec and Swagger UI generated from the code. |
| 10 | **Tested against the real thing** | Integration tests run against real Kafka and PostgreSQL containers via Testcontainers. |

## API

| Method | Path | Success | Errors |
| --- | --- | --- | --- |
| `POST` | `/v1/events` | `202 Accepted`, event is stored in Kafka | `400` invalid event, `503` Kafka did not acknowledge |

```json
{
  "eventId": "evt-0001",
  "accountId": "acc-demo",
  "meter": "sms",
  "quantity": 2,
  "occurredAt": "2026-10-03T10:00:00Z"
}
```

- `eventId` must be unique per account; it is the idempotency key.
- `quantity` is a positive whole number in the meter's smallest unit.
- `occurredAt` is when the usage happened, not when it was sent. It selects the tariff version and the billing month.

Swagger UI is at `http://localhost:8081/swagger-ui.html` and the raw spec at `http://localhost:8081/v3/api-docs` while `ingest` runs.

## Quick start

Requires JDK 21, Maven 3.9+ and a container runtime with Compose (Docker or Podman).

```sh
# 1. Start PostgreSQL and Kafka
docker compose up -d                  # or: podman compose up -d

# 2. Build and test everything
mvn -B verify

# 3. Run the two services (separate terminals)
java -jar ingest/target/ingest-0.1.0-SNAPSHOT.jar
java -jar rating/target/rating-0.1.0-SNAPSHOT.jar

# 4. Create a demo account and tariff (100 free SMS a month, then 0.05 each)
docker compose exec -T postgres psql -U ratekit -d ratekit < scripts/seed-demo.sql

# 5. Send events
curl -X POST localhost:8081/v1/events -H 'Content-Type: application/json' \
  -d '{"eventId":"e-1","accountId":"acc-demo","meter":"sms","quantity":95,"occurredAt":"2026-10-03T10:00:00Z"}'
curl -X POST localhost:8081/v1/events -H 'Content-Type: application/json' \
  -d '{"eventId":"e-2","accountId":"acc-demo","meter":"sms","quantity":10,"occurredAt":"2026-10-03T11:00:00Z"}'

# 6. Look at the charges: e-1 is free, e-2 pays for the 5 units over the quota
docker compose exec -T postgres psql -U ratekit -d ratekit \
  -c "SELECT event_id, quantity, amount FROM charges ORDER BY id;"
```

`rating` creates its own schema on first start (Flyway). Start it before step 4, which needs the tables. Sending the same `eventId` twice produces one charge.

**Podman:** the integration tests use Testcontainers. Point it at the Podman API socket:

```sh
DOCKER_HOST=unix:///var/run/docker.sock mvn -B verify
```

Compose services and their pinned images are described in [`docs/specs/local-dev.md`](docs/specs/local-dev.md).

## Testing

`mvn -B verify` runs unit tests and integration tests. The integration tests start real Kafka and PostgreSQL containers, so a running container runtime is required.

| Area | What is proven |
| --- | --- |
| Money and events | Rounding at the half-even boundaries, exact sums, invalid events rejected |
| Price models and tariff lookup | Tier and quota boundaries, version switch exactly at `effective_from`, no tariff found |
| Database schema | Duplicate events, negative balances and orphan charges are rejected by constraints |
| Ingest | `202` with the event in Kafka, same account in the same partition, `400` on invalid input, OpenAPI served |
| Rating | An event becomes one charge, a redelivery is rated once, quota is shared across events and resets monthly, the version at event time is used, a failing event leaves no trace |

## Status

| Done | Next |
| --- | --- |
| Maven multi-module build | Prepaid balance deduction that is safe under concurrency |
| PostgreSQL and Kafka via Compose | Bounded retries and a dead-letter topic |
| Event contract and money rules | Invoice run in `billing` |
| `ingest` with OpenAPI | Dockerfiles for all services |
| Versioned tariffs and three price models | GitHub Actions CI/CD |
| `rating` consumer, idempotent, with charges stored | Load test and observability |

Known limits today: a failing event is retried for a few seconds, holds up its partition meanwhile, and is then dropped with only a log line (fixed by the dead-letter work). Events that arrive out of order are rated in arrival order.

## Project layout

```
common/    shared event contract, money rules, topic names
ingest/    REST endpoint and Kafka producer
rating/    Kafka consumer, tariff domain, persistence, Flyway migrations
billing/   invoicing (skeleton)
docs/      research, implementation plan, ADRs, specs
scripts/   demo data
compose.yaml
```

Design decisions are recorded as ADRs in [`docs/adr/`](docs/adr/). The implementation plan is in [`docs/plans/`](docs/plans/).

## License

[Apache-2.0](LICENSE)
