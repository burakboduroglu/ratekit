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

> **Status: early development.** Ingest, rating, prepaid balance deduction and failure handling (retry and dead-letter) work end to end. Invoicing, container images and CI are still to come; see [Status](#status).

## What it is

An event enters through a REST endpoint and is written to Kafka. A rating service reads it, prices it against versioned tariffs, takes the charge from the account's prepaid balance and stores it in PostgreSQL. The pieces are separate services so each can scale and fail on its own, and Kafka sits between them so a slow or restarted service never loses an event.

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
| `rating` | Consumer, the pricing core | Reads events from Kafka, skips duplicates, finds the tariff version in force at the event's time, computes the charge, deducts it from the prepaid balance and stores it. Refuses events the account cannot afford and dead-letters events it cannot process. Owns the database schema. | 8082 |
| `billing` | Invoicing | Will turn a period's charges into invoices. Currently an empty Spring Boot skeleton. | 8083 (planned) |

## How an event flows

1. A client sends `POST /v1/events` with `eventId`, `accountId`, `meter`, `quantity` and `occurredAt`.
2. `ingest` validates it. An invalid event gets `400` and nothing is written.
3. `ingest` publishes it to the `usage-events` topic with `accountId` as the message key and waits for the broker to acknowledge. Only then does it return `202`. If Kafka does not answer, the client gets `503` and retries.
4. Kafka stores the event in one partition. All events of one account share a partition, so they are read in order, while different accounts are processed in parallel.
5. `rating` reads the event and tries to record `(accountId, eventId)` in `processed_events`. If that row already exists the event is a redelivery and is skipped.
6. Otherwise `rating` loads the tariff versions for the meter, picks the one in force at `occurredAt`, adds up what the account already used this month, and prices the event.
7. `rating` deducts the charge from the account's balance in one atomic statement. If the balance is too low the event is recorded as rejected and nothing is charged; otherwise the charge is stored. Steps 5 to 7 run in one database transaction, so a failure leaves no half-processed event behind.
8. If processing fails, the failure decides what happens: a transient one (the database blinks) is retried with growing pauses; a permanent one (unknown account, no tariff, unreadable message) goes straight to the `usage-events.dlq` dead-letter topic with the reason. After the last retry the record is dead-lettered too, and the partition moves on. See [ADR 0004](docs/adr/0004-retry-and-dead-letter.md).

## Highlights

| | Feature | How it works |
| --- | --- | --- |
| 1 | **At-least-once with idempotent consumers** | Kafka may deliver a message twice. The primary key `(account_id, event_id)` on `processed_events` makes the second attempt insert zero rows, so it is detected and skipped. ratekit never claims exactly-once. |
| 2 | **Ordering per account** | The account id is the Kafka key, so one account's events stay in one partition and are consumed in order. |
| 3 | **Versioned tariffs** | A tariff is never edited. A price change is a new row with a later `effective_from`, so an old event is always priced with the tariff that applied when it happened. |
| 4 | **Three price models** | Flat, graduated tiers, and a free quota followed by a flat rate. Each is a strategy behind one `PriceModel` interface. |
| 5 | **Usage accumulates across events** | Free quotas and tiers count the whole calendar month (UTC), so an event that crosses a quota or a tier boundary is split correctly. |
| 6 | **Exact money** | `BigDecimal` only, scale 4, half-even rounding, rounded once per charge. Unit prices keep full precision. See [ADR 0001](docs/adr/0001-money-and-rounding.md). |
| 7 | **Prepaid hard stop, safe under concurrency** | One `UPDATE ... WHERE balance >= x` checks and deducts in a single step, so racing events can never overspend a balance. A test fires 60 events at a balance that fits 10: exactly 10 are accepted. See [ADR 0002](docs/adr/0002-balance-deduction.md). |
| 8 | **Nothing is silently lost** | Transient failures are retried with exponential backoff; permanent ones are dead-lettered at once with the original key, the original coordinates and the stack trace in headers. An unreadable message is kept byte for byte. See [ADR 0004](docs/adr/0004-retry-and-dead-letter.md). |
| 9 | **Safety lives in the database** | `CHECK (balance >= 0)`, unique and foreign keys reject bad data even if application code is wrong. |
| 10 | **Framework-free domain** | The pricing logic has no Spring, Kafka or JDBC imports; a test fails the build if one appears. |
| 11 | **Documented API** | OpenAPI spec and Swagger UI generated from the code. |
| 12 | **Tested against the real thing** | Integration tests run against real Kafka and PostgreSQL containers via Testcontainers. |
| 13 | **Layered code, one job per class** | Controller, service, repository, mapper, DTO and config each live in their own package; see [Code structure](#code-structure) and [ADR 0003](docs/adr/0003-package-structure.md). |

## API

| Method | Path | Success | Errors |
| --- | --- | --- | --- |
| `POST` | `/v1/events` | `202 Accepted` with `{eventId, accountId, status: "ACCEPTED"}`; the event is stored in Kafka | `400` invalid event, `503` Kafka did not acknowledge |

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

To see what could not be processed, read the dead-letter topic (each record carries the original topic, partition, offset and the exception in its headers):

```sh
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic usage-events.dlq --from-beginning \
  --formatter-property print.key=true --formatter-property print.headers=true
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
| Balance | An affordable event is deducted, an exact-balance event leaves zero, an unaffordable one is rejected and recorded, a free event passes with an empty balance, a rejected event is not revived by a redelivery, 60 racing events never overspend |
| Failure handling | An unknown account and a missing tariff are dead-lettered without retrying, an unreadable message is dead-lettered with its original bytes, a transient failure is retried until it succeeds, exhausted retries end in the dead-letter topic, and in every case the next event on the partition is still rated |

## Status

| Done | Next |
| --- | --- |
| Maven multi-module build | Invoice run in `billing` |
| PostgreSQL and Kafka via Compose | Dockerfiles for all services |
| Event contract and money rules | GitHub Actions CI/CD |
| `ingest` with OpenAPI | Load test and observability |
| Versioned tariffs and three price models | |
| `rating` consumer, idempotent, with charges stored | |
| Prepaid hard stop, atomic and concurrency-safe | |
| Retry with backoff and a dead-letter topic | |

Known limits today: a transient failure can hold up its partition for up to 7.5 seconds (configurable). Dead letters are inspected and replayed by hand. Events that arrive out of order are rated in arrival order. Rejections for insufficient balance are not reported back to the sender, who already received `202`.

## Code structure

Each service is split into layers, one package per layer, and a class does one job. The pricing rules sit in a pure `domain` package that knows nothing about Spring, Kafka or the database.

```
ingest/  io.github.burakboduroglu.ratekit.ingest
  controller/   EventController
  dto/          EventRequest, EventResponse
  mapper/       EventMapper
  service/      EventIngestService
  messaging/    EventPublisher
  config/       KafkaTopicConfig, KafkaProducerConfig
  exception/    EventPublishException, ApiExceptionHandler

rating/  io.github.burakboduroglu.ratekit.rating
  messaging/    UsageEventListener, DeadLetterProducer
  service/      RatingService
  repository/   AccountRepository, ChargeRepository, ProcessedEventRepository,
                RejectedEventRepository, TariffRepository
  mapper/       TariffMapper
  config/       KafkaTopicConfig, DeadLetterConfig, ConsumerErrorHandlingConfig, RetryProperties
  exception/    UnknownAccountException
  domain/       PriceModel, FlatPrice, TieredPrice, FreeQuotaThenFlat,
                Tariff, TariffBook, Rater, Charge, BillingPeriod, NoTariffException

common/  io.github.burakboduroglu.ratekit.common
                UsageEvent, Money, Topics
```

| Layer | Responsibility | Rule |
| --- | --- | --- |
| `controller` | HTTP entry point | Translates the request, calls a service, translates the answer. No business logic. |
| `dto` | API request and response shapes | Plain data, no behaviour, no conversion. |
| `mapper` | Conversions between layers | One conversion concern per class (request to event, database row to tariff). |
| `service` | Use cases and transactions | Coordinates repositories and the domain. |
| `repository` | Database access | SQL only. |
| `messaging` | Kafka producer and listener | Adapters between Kafka and the service layer. |
| `config` | Spring configuration | One concern per class. |
| `exception` | Exception types and their HTTP translation | Controllers stay free of error handling. |
| `domain` | Pricing rules | Plain Java, enforced by a test that fails on framework imports. |

Dependencies point inward: controller and listener call the service, the service calls repositories and the domain, and the domain depends on nothing. How a request moves through the layers in `ingest`:

```
HTTP request
    -> EventController            validates the body (EventRequest)
    -> EventMapper                EventRequest -> UsageEvent
    -> EventIngestService         the use case
    -> EventPublisher             writes to Kafka, waits for the acknowledgement
    <- EventMapper                UsageEvent -> EventResponse
HTTP 202 with EventResponse
```

## Design principles and patterns

| Pattern or principle | Where | Why |
| --- | --- | --- |
| Strategy | `PriceModel` with `FlatPrice`, `TieredPrice`, `FreeQuotaThenFlat` | A new pricing model is a new class; `Rater` does not change. |
| Repository | `repository` package | SQL is isolated from business logic and swappable in tests. |
| DTO and mapper | `dto`, `mapper` | The API shape can change without touching the domain, and the other way round. |
| Service layer | `service` package | One place for use cases and transaction boundaries. |
| Adapter | `messaging` package | Kafka details stay at the edge. |
| Value object | `Money`, `UsageEvent` | Immutable records that validate themselves on construction. |
| Idempotent consumer | `RatingService` with `processed_events` | A redelivered message is detected and skipped. |
| Constructor injection | every Spring bean | Dependencies are explicit and easy to replace with test doubles. |
| Fail fast at the boundary | `EventRequest` bean validation, `UsageEvent` invariants | Bad input is rejected before it reaches Kafka. |

SOLID, as applied here:

| Principle | Example |
| --- | --- |
| Single responsibility | `TariffRepository` runs SQL; `TariffMapper` parses JSON; `KafkaTopicConfig` and `KafkaProducerConfig` each configure one thing. |
| Open/closed | Adding a price model means adding a `PriceModel` implementation; only the mapper learns its stored name. |
| Liskov substitution | Any `PriceModel` can stand in for another inside `Rater`. |
| Interface segregation | `PriceModel` has a single method. |
| Dependency inversion | The domain depends on nothing outward. Repositories are concrete classes injected by Spring and are not hidden behind interfaces: with one implementation each, an interface would add ceremony without a benefit. |

## Data model

| Table | Purpose | Key rule |
| --- | --- | --- |
| `accounts` | Prepaid balance per account | `CHECK (balance >= 0)` |
| `tariffs` | Tariff versions per meter, model parameters as JSON | Unique `(meter, effective_from)`; never edited, a price change is a new row |
| `processed_events` | Idempotency ledger | Primary key `(account_id, event_id)` |
| `charges` | One priced charge per processed event | Foreign key to `processed_events`, unique `(account_id, event_id)` |
| `rejected_events` | Events refused for lack of balance, with what they would have cost | Foreign key to `processed_events` |

Tariff parameters are stored as JSON, one shape per model:

| Model | `params` |
| --- | --- |
| `FLAT` | `{"rate": "0.05"}` |
| `FREE_QUOTA_THEN_FLAT` | `{"freeUnits": 100, "rate": "0.10"}` |
| `TIERED` | `{"tiers": [{"upTo": 100, "rate": "0.10"}, {"upTo": null, "rate": "0.05"}]}`, bounds ascending, the last tier unbounded |

Rates keep full precision; only the final charge is rounded (scale 4, half-even). Schema changes are Flyway migrations in `rating/src/main/resources/db/migration`.

## Configuration

Defaults suit the Compose setup. Any property can be overridden with a Spring environment variable (for example `SPRING_KAFKA_BOOTSTRAP_SERVERS`).

| Property | Default | Service |
| --- | --- | --- |
| `server.port` | `8081` / `8082` | ingest / rating |
| `spring.kafka.bootstrap-servers` | `localhost:9092` | both |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/ratekit` | rating |
| `spring.datasource.username`, `password` | `ratekit` | rating |
| `spring.kafka.consumer.group-id` | `ratekit-rating` | rating |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | rating |
| `ratekit.rating.retry.max-retries` | `4` | rating |
| `ratekit.rating.retry.initial-interval-ms` | `500` | rating |
| `ratekit.rating.retry.multiplier` | `2.0` | rating |
| `ratekit.rating.retry.max-interval-ms` | `5000` | rating |
| `spring.kafka.producer.acks` | `all`, with idempotent producer | ingest |

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

Design decisions are recorded as ADRs in [`docs/adr/`](docs/adr/): money and rounding, balance deduction, package structure, retry and dead-letter policy. The implementation plan is in [`docs/plans/`](docs/plans/).

## License

[Apache-2.0](LICENSE)
